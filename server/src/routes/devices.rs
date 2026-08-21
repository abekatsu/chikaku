use serde::{Deserialize, Serialize};
use worker::{D1Database, Response};

use crate::clock;
use crate::db;
use crate::error::{AppError, AppResult};
use crate::token;
use crate::validate::{MAX_NAME_LEN, clamp_text, require_text};

/// Android の `RegisterDeviceRequest` と対。
#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    pub invite_code: String,
    pub device_name: String,
    pub device_model: String,
}

/// Android の `RegisterDeviceResponse` と対。
#[derive(Debug, Serialize)]
pub struct RegisterResponse {
    pub device_id: String,
    pub device_token: String,
    pub family_id: String,
}

#[derive(Deserialize)]
struct InviteRow {
    family_id: String,
}

/// `POST /api/v1/devices/register`
///
/// 招待コードを 1 回きりの引換券として端末を家族に紐づける。
/// 端末トークンは原文をここで一度だけ返し、サーバーには SHA-256 しか残さない。
///
/// このエンドポイントは Cloudflare Access のバイパス対象 (ADR-3)。
/// アプリは対話的ログインができないため、招待コードが唯一の関門になる。
pub async fn register(database: &D1Database, req: RegisterRequest) -> AppResult<Response> {
    let device_name = require_text(
        &req.device_name,
        MAX_NAME_LEN,
        "端末の名前を入力してください。",
    )?;
    // 機種名は端末が自動で埋める補助情報なので、空でも登録は止めない。
    let device_model = clamp_text(&req.device_model, MAX_NAME_LEN);

    let code = token::normalize_invite_code(&req.invite_code);
    if code.is_empty() {
        return Err(AppError::InvalidInviteCode);
    }

    let now = clock::now();

    // 期限内かつ未使用のものだけを引き当てる。
    let invite: Option<InviteRow> = db::first(
        database,
        "SELECT family_id FROM invite_codes \
         WHERE code = ?1 AND used_at IS NULL AND expires_at > ?2",
        &[db::text(&code), db::num(now)],
    )
    .await?;
    let family_id = invite.ok_or(AppError::InvalidInviteCode)?.family_id;

    let device_id = token::new_id();
    let device_token = token::generate_secret()?;

    // 端末の作成と招待コードの消費を 1 つの batch にまとめる。
    // D1 の batch は SQL トランザクションだが、**ロールバックされるのは
    // 文が失敗したときだけ**で「更新 0 行」では起きない (ADR-4)。
    // そのため引き換えの成否を行数で表現する:
    //
    //   1. INSERT ... SELECT で、招待コードが未使用かつ期限内のときだけ端末を作る
    //   2. UPDATE は「その端末が実際に作られたこと」を EXISTS で確認してから消費する
    //
    // 同一トランザクション内なので両者は同じ状態を見る。結果として
    // 「端末だけ作られた」「コードだけ消費された」のどちらも起こらない。
    let counts = db::batch(
        database,
        vec![
            database
                .prepare(
                    "INSERT INTO parent_devices \
                     (id, family_id, device_name, device_model, token_hash, created_at) \
                     SELECT ?1, family_id, ?2, ?3, ?4, ?5 FROM invite_codes \
                     WHERE code = ?6 AND used_at IS NULL AND expires_at > ?5",
                )
                .bind(&[
                    db::text(&device_id),
                    db::text(&device_name),
                    db::text(&device_model),
                    db::text(&token::hash_secret(&device_token)),
                    db::num(now),
                    db::text(&code),
                ])?,
            database
                .prepare(
                    "UPDATE invite_codes SET used_at = ?1, used_by_device = ?2 \
                     WHERE code = ?3 AND used_at IS NULL \
                       AND EXISTS (SELECT 1 FROM parent_devices WHERE id = ?2)",
                )
                .bind(&[db::num(now), db::text(&device_id), db::text(&code)])?,
        ],
    )
    .await?;

    let inserted = counts.first().copied().unwrap_or(0);
    let consumed = counts.get(1).copied().unwrap_or(0);
    if inserted != 1 || consumed != 1 {
        // 先の SELECT から batch までの間に他の端末が使い切った。
        return Err(AppError::InvalidInviteCode);
    }

    worker::console_log!("親端末を登録しました device_id={device_id} family_id={family_id}");
    Ok(Response::from_json(&RegisterResponse {
        device_id,
        device_token,
        family_id,
    })?)
}
