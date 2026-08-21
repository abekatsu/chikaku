use serde::{Deserialize, Serialize};
use worker::{D1Database, Response};

use crate::auth::DeviceAuth;
use crate::clock;
use crate::db;
use crate::error::{AppError, AppResult};

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
/// Cloudflare Access のバイパス対象で、`device_token` だけが関門になる (ADR-3)。
pub async fn create(
    database: &D1Database,
    auth: &DeviceAuth,
    retention_ms: i64,
    req: LocationRequest,
) -> AppResult<Response> {
    // トークンの持ち主と名乗った端末が食い違う場合は、他端末になりすました
    // 投稿とみなして 401 にする。端末側はペアリングをやり直す。
    if req.device_id != auth.device_id {
        return Err(AppError::Unauthorized);
    }

    let now = clock::now();
    let recorded_at = clock::parse_rfc3339(&req.timestamp)
        .ok_or_else(|| AppError::BadRequest("時刻の形式が正しくありません。".to_owned()))?;

    // 保持期間より古いものは入れても即座に消えるだけなので受け取らない。
    if recorded_at > now + MAX_CLOCK_SKEW_MS || recorded_at < now - retention_ms {
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

    // 応答が失われたあとの再送で重複しないよう、
    // (device_id, recorded_at) の一意制約に任せて黙って捨てる。
    let counts = db::batch(
        database,
        vec![
            database
                .prepare(
                    "INSERT OR IGNORE INTO location_events \
                     (device_id, family_id, lat, lng, accuracy, recorded_at, received_at, battery_level) \
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
                )
                .bind(&[
                    db::text(&auth.device_id),
                    db::text(&auth.family_id),
                    db::real(req.lat),
                    db::real(req.lng),
                    db::real(req.accuracy),
                    db::num(recorded_at),
                    db::num(now),
                    db::num(req.battery_level),
                ])?,
            database
                .prepare("UPDATE parent_devices SET last_seen_at = ?1 WHERE id = ?2")
                .bind(&[db::num(now), db::text(&auth.device_id)])?,
        ],
    )
    .await?;

    let stored = counts.first().copied().unwrap_or(0) == 1;
    // 位置そのものはログに残さない (CLAUDE.md §5)。
    worker::console_log!(
        "位置情報を受信 device_id={} stored={stored}",
        auth.device_id
    );

    Ok(Response::from_json(&LocationAccepted { stored })?.with_status(202))
}
