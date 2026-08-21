use crate::error::AppError;
use sha2::{Digest, Sha256};

/// 招待コードで使う文字集合。
/// 高齢の利用者が紙やメモから読み取って手入力するため、
/// 見間違えやすい 0 1 2 と B I L O S Z を最初から除いた 27 文字。
const INVITE_ALPHABET: &[u8] = b"3456789ACDEFGHJKMNPQRTUVWXY";

fn random_bytes(buf: &mut [u8]) -> Result<(), AppError> {
    getrandom::fill(buf).map_err(|e| AppError::Internal(format!("乱数の取得に失敗: {e}")))
}

/// 親端末の `device_token`。32 バイトの OS 乱数を hex 化して返す。
pub fn generate_secret() -> Result<String, AppError> {
    let mut buf = [0u8; 32];
    random_bytes(&mut buf)?;
    Ok(hex::encode(buf))
}

/// 8 桁の招待コード。27^8 ≒ 2.8e11 通り。
/// 総当たりされないよう有効期限を短く保つ運用と併せて使う。
pub fn generate_invite_code() -> Result<String, AppError> {
    let mut buf = [0u8; 8];
    random_bytes(&mut buf)?;
    // アルファベットの長さが 2 の冪ではないため剰余に僅かな偏りが出るが、
    // 期限付き招待コードの用途では実害がない範囲として許容する。
    Ok(buf
        .iter()
        .map(|b| INVITE_ALPHABET[*b as usize % INVITE_ALPHABET.len()] as char)
        .collect())
}

/// 入力された招待コードの表記ゆれを吸収する。
/// 大文字小文字と、読みやすさのために入れられた空白・ハイフンを落とす。
pub fn normalize_invite_code(input: &str) -> String {
    input
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .collect()
}

/// トークンは原文を保存せず、この SHA-256 hex だけを DB に持つ。
/// 入力が 32 バイトの一様乱数なので、パスワードと違い伸長は不要。
pub fn hash_secret(secret: &str) -> String {
    hex::encode(Sha256::digest(secret.as_bytes()))
}

/// `Authorization: Bearer <token>` を取り出す。
/// スキーム名は大文字小文字を区別しない (RFC 7235)。
pub fn bearer(header: Option<String>) -> Result<String, AppError> {
    header
        .as_deref()
        .and_then(|v| {
            let (scheme, token) = v.split_once(' ')?;
            scheme
                .eq_ignore_ascii_case("bearer")
                .then_some(token.trim())
        })
        .filter(|t| !t.is_empty())
        .map(str::to_owned)
        .ok_or(AppError::Unauthorized)
}

/// UUID v4 を文字列で返す。
pub fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invite_code_is_unambiguous_and_fixed_length() {
        let code = generate_invite_code().unwrap();
        assert_eq!(code.len(), 8);
        assert!(
            code.chars().all(|c| INVITE_ALPHABET.contains(&(c as u8))),
            "見間違えやすい文字が混ざっている: {code}"
        );
    }

    #[test]
    fn normalize_accepts_how_people_actually_type_it() {
        assert_eq!(normalize_invite_code("a3c4-d5e6"), "A3C4D5E6");
        assert_eq!(normalize_invite_code(" A3C4 D5E6 "), "A3C4D5E6");
    }

    #[test]
    fn secrets_differ_and_hash_stably() {
        let a = generate_secret().unwrap();
        let b = generate_secret().unwrap();
        assert_ne!(a, b);
        assert_eq!(a.len(), 64);
        assert_eq!(hash_secret(&a), hash_secret(&a));
        assert_ne!(hash_secret(&a), hash_secret(&b));
    }

    #[test]
    fn bearer_parsing() {
        assert_eq!(bearer(Some("Bearer abc".into())).unwrap(), "abc");
        assert_eq!(bearer(Some("bearer abc".into())).unwrap(), "abc");
        assert!(bearer(Some("Basic abc".into())).is_err());
        assert!(bearer(Some("Bearer   ".into())).is_err());
        assert!(bearer(None).is_err());
    }
}
