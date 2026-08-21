use axum::Json;
use axum::extract::State;
use serde::{Deserialize, Serialize};

use crate::auth::token::{generate_secret, hash_secret, normalize_invite_code};
use crate::clock;
use crate::error::{AppError, AppResult};
use crate::state::AppState;
use crate::validate::{MAX_NAME_LEN, require_text};

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

/// `POST /api/v1/devices/register`
///
/// 招待コードを 1 回きりの引換券として端末を家族に紐づける。
/// 端末トークンは原文をここで一度だけ返し、サーバーには SHA-256 しか残さない。
pub async fn register(
    State(state): State<AppState>,
    Json(req): Json<RegisterRequest>,
) -> AppResult<Json<RegisterResponse>> {
    let device_name = require_text(
        &req.device_name,
        MAX_NAME_LEN,
        "端末の名前を入力してください。",
    )?;
    // 機種名は端末が自動で埋める補助情報なので、空でも登録は止めない。
    let device_model: String = req.device_model.trim().chars().take(MAX_NAME_LEN).collect();

    let code = normalize_invite_code(&req.invite_code);
    if code.is_empty() {
        return Err(AppError::InvalidInviteCode);
    }

    let now = clock::now();
    let mut tx = state.db.begin().await?;

    // 期限内かつ未使用のものだけを引き当てる。
    let family_id: Option<(String,)> = sqlx::query_as(
        "SELECT family_id FROM invite_codes \
         WHERE code = ?1 AND used_at IS NULL AND expires_at > ?2",
    )
    .bind(&code)
    .bind(now)
    .fetch_optional(&mut *tx)
    .await?;
    let (family_id,) = family_id.ok_or(AppError::InvalidInviteCode)?;

    let device_id = uuid::Uuid::new_v4().to_string();
    let device_token = generate_secret()?;

    sqlx::query(
        "INSERT INTO parent_devices \
         (id, family_id, device_name, device_model, token_hash, created_at) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
    )
    .bind(&device_id)
    .bind(&family_id)
    .bind(&device_name)
    .bind(&device_model)
    .bind(hash_secret(&device_token))
    .bind(now)
    .execute(&mut *tx)
    .await?;

    // used_at IS NULL を条件に更新し、同時に同じコードを使われても
    // 片方だけが成立するようにする。
    let used = sqlx::query(
        "UPDATE invite_codes SET used_at = ?1, used_by_device = ?2 \
         WHERE code = ?3 AND used_at IS NULL",
    )
    .bind(now)
    .bind(&device_id)
    .bind(&code)
    .execute(&mut *tx)
    .await?;
    if used.rows_affected() != 1 {
        return Err(AppError::InvalidInviteCode);
    }

    tx.commit().await?;

    tracing::info!(%device_id, %family_id, "親端末を登録しました");
    Ok(Json(RegisterResponse {
        device_id,
        device_token,
        family_id,
    }))
}
