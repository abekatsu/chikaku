use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use serde::{Deserialize, Serialize};

use crate::auth::extract::DeviceAuth;
use crate::clock;
use crate::error::{AppError, AppResult};
use crate::state::AppState;

/// 端末の時計が進んでいても受け入れる上限。
/// これを超えるものは明らかな異常として弾く。
const MAX_CLOCK_SKEW_MS: i64 = 24 * 60 * 60 * 1_000;

/// Android の `LocationPayload` と対 (CLAUDE.md §2.4)。
#[derive(Debug, Deserialize)]
pub struct LocationRequest {
    pub device_id: String,
    pub lat: f64,
    pub lng: f64,
    pub accuracy: f64,
    /// ISO-8601 (UTC)
    pub timestamp: String,
    pub battery_level: i64,
}

#[derive(Debug, Serialize)]
pub struct LocationAccepted {
    /// 重複排除で既存の行に畳まれた場合は false。端末側の挙動は変わらないが、
    /// 再送が効いていることをログや検証で確認できるようにしておく。
    pub stored: bool,
}

/// `POST /api/v1/location`
///
/// 端末は 1 件ずつ送る。オフライン中に溜めた分は同じ呼び出しの繰り返しで消化される。
pub async fn create(
    State(state): State<AppState>,
    auth: DeviceAuth,
    Json(req): Json<LocationRequest>,
) -> AppResult<(StatusCode, Json<LocationAccepted>)> {
    // トークンの持ち主と名乗った端末が食い違う場合は、他端末になりすました
    // 投稿とみなして 401 にする。端末側はペアリングをやり直す。
    if req.device_id != auth.device_id {
        return Err(AppError::Unauthorized);
    }

    let now = clock::now();
    let recorded_at = clock::parse_rfc3339(&req.timestamp)
        .ok_or_else(|| AppError::BadRequest("時刻の形式が正しくありません。".to_owned()))?;

    // 保持期間より古いものは入れても即座に消えるだけなので受け取らない。
    let oldest = now - state.config.retention.as_millis() as i64;
    if recorded_at > now + MAX_CLOCK_SKEW_MS || recorded_at < oldest {
        return Err(AppError::BadRequest(
            "端末の時刻が大きくずれています。日付と時刻の設定をご確認ください。".to_owned(),
        ));
    }

    if !(-90.0..=90.0).contains(&req.lat) || !(-180.0..=180.0).contains(&req.lng) {
        return Err(AppError::BadRequest(
            "位置の値が正しくありません。".to_owned(),
        ));
    }
    if !req.accuracy.is_finite() || req.accuracy < 0.0 {
        return Err(AppError::BadRequest(
            "精度の値が正しくありません。".to_owned(),
        ));
    }
    // 端末は取得できないとき -1 を送ってくる。
    if !(-1..=100).contains(&req.battery_level) {
        return Err(AppError::BadRequest(
            "電池残量の値が正しくありません。".to_owned(),
        ));
    }

    let mut tx = state.db.begin().await?;

    // 応答が失われたあとの再送で重複しないよう、
    // (device_id, recorded_at) の一意制約に任せて黙って捨てる。
    let inserted = sqlx::query(
        "INSERT OR IGNORE INTO location_events \
         (device_id, family_id, lat, lng, accuracy, recorded_at, received_at, battery_level) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
    )
    .bind(&auth.device_id)
    .bind(&auth.family_id)
    .bind(req.lat)
    .bind(req.lng)
    .bind(req.accuracy)
    .bind(recorded_at)
    .bind(now)
    .bind(req.battery_level)
    .execute(&mut *tx)
    .await?;

    sqlx::query("UPDATE parent_devices SET last_seen_at = ?1 WHERE id = ?2")
        .bind(now)
        .bind(&auth.device_id)
        .execute(&mut *tx)
        .await?;

    tx.commit().await?;

    let stored = inserted.rows_affected() == 1;
    // 位置そのものはログに残さない (CLAUDE.md §5)。
    tracing::debug!(device_id = %auth.device_id, stored, "位置情報を受信しました");

    Ok((StatusCode::ACCEPTED, Json(LocationAccepted { stored })))
}
