//! Cloudflare Access が付けてくる JWT の検証 (ADR-3)。
//!
//! Worker が Static Assets を持つ場合、Access のコンテキスト (`ctx.access`) は
//! Worker に渡らない。したがってこのモジュールが `Cf-Access-Jwt-Assertion`
//! ヘッダの JWT を自分で検証する。
//!
//! **ヘッダを読むだけでは不十分。** 署名を検証しないと、ヘッダを付けた
//! リクエストを直接投げるだけで誰でもなりすませる。

use base64::Engine;
use js_sys::{Object, Reflect, Uint8Array};
use serde::Deserialize;
use wasm_bindgen::{JsCast, JsValue};
use wasm_bindgen_futures::JsFuture;
use worker::{CfProperties, Fetch, Request, RequestInit};

use crate::clock;
use crate::error::AppError;

/// JWKS の取得結果を Cloudflare のキャッシュに預ける秒数。
/// 鍵のローテーションに追随しつつ、毎リクエストの取得は避ける。
const JWKS_CACHE_TTL_SECONDS: i32 = 3_600;

/// 検証済みの Access アイデンティティ。
#[derive(Debug, Clone)]
pub struct AccessIdentity {
    pub email: String,
}

#[derive(Deserialize)]
struct Claims {
    email: Option<String>,
    iss: String,
    exp: i64,
    aud: serde_json::Value,
    /// サービストークンで認証された場合に入る。人間ではないので拒否する。
    #[serde(default)]
    common_name: Option<String>,
}

fn b64url(s: &str) -> Result<Vec<u8>, AppError> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(s)
        .map_err(|_| AppError::Unauthorized)
}

/// `Cf-Access-Jwt-Assertion` の JWT を検証して、認証された人のメールアドレスを返す。
pub async fn verify(
    jwt: &str,
    team_domain: &str,
    policy_aud: &str,
) -> Result<AccessIdentity, AppError> {
    let parts: Vec<&str> = jwt.split('.').collect();
    if parts.len() != 3 {
        return Err(AppError::Unauthorized);
    }

    let header: serde_json::Value =
        serde_json::from_slice(&b64url(parts[0])?).map_err(|_| AppError::Unauthorized)?;
    // alg を JWT 側の申告に従わせない。RS256 以外は受け付けない
    // (alg=none や HS256 への差し替え攻撃を塞ぐ)。
    if header["alg"].as_str() != Some("RS256") {
        return Err(AppError::Unauthorized);
    }
    let kid = header["kid"].as_str().ok_or(AppError::Unauthorized)?;

    let jwks = fetch_jwks(team_domain).await?;
    let key = jwks["keys"]
        .as_array()
        .ok_or_else(|| AppError::Internal("JWKS の形式が不正です".into()))?
        .iter()
        .find(|k| k["kid"].as_str() == Some(kid))
        .ok_or(AppError::Unauthorized)?;

    if !verify_signature(key, parts[0], parts[1], parts[2]).await? {
        return Err(AppError::Unauthorized);
    }

    let claims: Claims =
        serde_json::from_slice(&b64url(parts[1])?).map_err(|_| AppError::Unauthorized)?;

    // aud は文字列でも配列でも来る。
    let aud_ok = match &claims.aud {
        serde_json::Value::String(s) => s == policy_aud,
        serde_json::Value::Array(a) => a.iter().any(|v| v.as_str() == Some(policy_aud)),
        _ => false,
    };
    if !aud_ok || claims.iss != team_domain {
        return Err(AppError::Unauthorized);
    }
    if claims.exp.saturating_mul(1_000) < clock::now() {
        return Err(AppError::Unauthorized);
    }
    // サービストークンは sub が空で common_name が入る。
    // ダッシュボードは人が使うものなので、機械の資格情報は受け付けない。
    if claims.common_name.is_some() {
        return Err(AppError::Unauthorized);
    }

    let email = claims.email.ok_or(AppError::Unauthorized)?;
    Ok(AccessIdentity {
        email: email.trim().to_lowercase(),
    })
}

async fn fetch_jwks(team_domain: &str) -> Result<serde_json::Value, AppError> {
    let url = format!("{}/cdn-cgi/access/certs", team_domain.trim_end_matches('/'));
    let mut init = RequestInit::new();
    init.with_cf_properties(CfProperties {
        cache_ttl: Some(JWKS_CACHE_TTL_SECONDS),
        cache_everything: Some(true),
        ..Default::default()
    });
    let request = Request::new_with_init(&url, &init)?;
    let mut response = Fetch::Request(request).send().await?;
    if response.status_code() != 200 {
        return Err(AppError::Internal(format!(
            "JWKS の取得に失敗しました: {} {}",
            response.status_code(),
            url
        )));
    }
    response
        .json::<serde_json::Value>()
        .await
        .map_err(|e| AppError::Internal(format!("JWKS を解釈できません: {e}")))
}

/// RS256 の署名検証を Web Crypto に任せる。
async fn verify_signature(
    jwk: &serde_json::Value,
    header_b64: &str,
    payload_b64: &str,
    signature_b64: &str,
) -> Result<bool, AppError> {
    let subtle = subtle_crypto()?;

    let algorithm = Object::new();
    set(&algorithm, "name", &"RSASSA-PKCS1-v1_5".into())?;
    let hash = Object::new();
    set(&hash, "name", &"SHA-256".into())?;
    set(&algorithm, "hash", &hash)?;

    // serde_wasm_bindgen は JS の Map を作ってしまい SubtleCrypto が
    // kty を読めないため、JSON.parse で素のオブジェクトにする (ADR-5)。
    let jwk_js = js_sys::JSON::parse(&jwk.to_string())
        .map_err(|_| AppError::Internal("JWK を解釈できません".into()))?;

    let key = call_promise(
        &subtle,
        "importKey",
        &js_sys::Array::of5(
            &"jwk".into(),
            &jwk_js,
            &algorithm,
            &JsValue::FALSE,
            &js_sys::Array::of1(&"verify".into()),
        ),
    )
    .await?;

    let signature = Uint8Array::from(b64url(signature_b64)?.as_slice());
    let signed = format!("{header_b64}.{payload_b64}");
    let data = Uint8Array::from(signed.as_bytes());

    let ok = call_promise(
        &subtle,
        "verify",
        &js_sys::Array::of4(&algorithm, &key, &signature, &data),
    )
    .await?;

    Ok(ok == JsValue::TRUE)
}

fn subtle_crypto() -> Result<JsValue, AppError> {
    let crypto = Reflect::get(&js_sys::global(), &"crypto".into())
        .map_err(|_| AppError::Internal("crypto がありません".into()))?;
    Reflect::get(&crypto, &"subtle".into())
        .map_err(|_| AppError::Internal("crypto.subtle がありません".into()))
}

fn set(target: &Object, key: &str, value: &JsValue) -> Result<(), AppError> {
    Reflect::set(target, &key.into(), value)
        .map(|_| ())
        .map_err(|_| AppError::Internal(format!("{key} を設定できません")))
}

async fn call_promise(
    target: &JsValue,
    method: &str,
    args: &js_sys::Array,
) -> Result<JsValue, AppError> {
    let func: js_sys::Function = Reflect::get(target, &method.into())
        .map_err(|_| AppError::Internal(format!("{method} がありません")))?
        .dyn_into()
        .map_err(|_| AppError::Internal(format!("{method} が関数ではありません")))?;
    let promise = func
        .apply(target, args)
        .map_err(|e| AppError::Internal(format!("{method} の呼び出しに失敗: {e:?}")))?;
    JsFuture::from(js_sys::Promise::from(promise))
        .await
        .map_err(|e| AppError::Internal(format!("{method} が失敗: {e:?}")))
}
