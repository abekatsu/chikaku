use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use serde::{Deserialize, Serialize};

use crate::auth::extract::ChildAuth;
use crate::auth::token::generate_invite_code;
use crate::clock::{self, Millis};
use crate::error::{AppError, AppResult};
use crate::state::AppState;

const DEFAULT_HISTORY_WINDOW_MS: Millis = 24 * 60 * 60 * 1_000;
const DEFAULT_HISTORY_LIMIT: i64 = 1_000;
const MAX_HISTORY_LIMIT: i64 = 5_000;

// ---------------------------------------------------------------- latest

#[derive(Debug, Serialize)]
pub struct LatestResponse {
    pub family_id: String,
    pub devices: Vec<DeviceLatest>,
}

#[derive(Debug, Serialize)]
pub struct DeviceLatest {
    pub device_id: String,
    pub device_name: String,
    pub device_model: String,
    /// 最後にサーバーと通信できた時刻。位置が動かなくても更新される。
    pub last_seen_at: Option<String>,
    /// 一度も送信していない端末では null。
    pub latest: Option<Fix>,
}

#[derive(Debug, Serialize)]
pub struct Fix {
    pub lat: f64,
    pub lng: f64,
    pub accuracy: f64,
    /// 端末が測位した時刻
    pub recorded_at: String,
    /// サーバーが受信した時刻。圏外で溜まっていた分はここが大きく遅れる。
    pub received_at: String,
    pub battery_level: i64,
}

#[derive(sqlx::FromRow)]
struct LatestRow {
    id: String,
    device_name: String,
    device_model: String,
    last_seen_at: Option<Millis>,
    lat: Option<f64>,
    lng: Option<f64>,
    accuracy: Option<f64>,
    recorded_at: Option<Millis>,
    received_at: Option<Millis>,
    battery_level: Option<i64>,
}

/// `GET /api/v1/families/{family_id}/latest`
pub async fn latest(
    State(state): State<AppState>,
    auth: ChildAuth,
    Path(family_id): Path<String>,
) -> AppResult<Json<LatestResponse>> {
    auth.scope(&family_id)?;

    let rows: Vec<LatestRow> = sqlx::query_as(
        "SELECT d.id, d.device_name, d.device_model, d.last_seen_at, \
                e.lat, e.lng, e.accuracy, e.recorded_at, e.received_at, e.battery_level \
         FROM parent_devices d \
         LEFT JOIN location_events e ON e.id = ( \
             SELECT id FROM location_events \
             WHERE device_id = d.id ORDER BY recorded_at DESC LIMIT 1 \
         ) \
         WHERE d.family_id = ?1 AND d.revoked_at IS NULL \
         ORDER BY d.created_at",
    )
    .bind(&family_id)
    .fetch_all(&state.db)
    .await?;

    let devices = rows
        .into_iter()
        .map(|r| DeviceLatest {
            device_id: r.id,
            device_name: r.device_name,
            device_model: r.device_model,
            last_seen_at: r.last_seen_at.map(clock::to_rfc3339),
            // LEFT JOIN なので位置の各列は揃って NULL か揃って値が入る。
            latest: match (r.lat, r.lng, r.accuracy, r.recorded_at, r.received_at) {
                (Some(lat), Some(lng), Some(accuracy), Some(recorded), Some(received)) => {
                    Some(Fix {
                        lat,
                        lng,
                        accuracy,
                        recorded_at: clock::to_rfc3339(recorded),
                        received_at: clock::to_rfc3339(received),
                        battery_level: r.battery_level.unwrap_or(-1),
                    })
                }
                _ => None,
            },
        })
        .collect();

    Ok(Json(LatestResponse { family_id, devices }))
}

// --------------------------------------------------------------- history

#[derive(Debug, Deserialize)]
pub struct HistoryQuery {
    pub from: Option<String>,
    pub to: Option<String>,
    /// 指定すると 1 端末に絞る。
    pub device_id: Option<String>,
    pub limit: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct HistoryResponse {
    pub family_id: String,
    pub from: String,
    pub to: String,
    /// 上限に達して打ち切られたか。true ならクライアントは範囲を狭めて引き直す。
    pub truncated: bool,
    pub events: Vec<HistoryEvent>,
}

#[derive(Debug, Serialize)]
pub struct HistoryEvent {
    pub device_id: String,
    pub lat: f64,
    pub lng: f64,
    pub accuracy: f64,
    pub recorded_at: String,
    pub battery_level: i64,
}

#[derive(sqlx::FromRow)]
struct HistoryRow {
    device_id: String,
    lat: f64,
    lng: f64,
    accuracy: f64,
    recorded_at: Millis,
    battery_level: i64,
}

/// `GET /api/v1/families/{family_id}/history?from=&to=&device_id=&limit=`
///
/// 期間を省略すると直近 24 時間。時刻は RFC 3339 で受け取る。
pub async fn history(
    State(state): State<AppState>,
    auth: ChildAuth,
    Path(family_id): Path<String>,
    Query(q): Query<HistoryQuery>,
) -> AppResult<Json<HistoryResponse>> {
    auth.scope(&family_id)?;

    let to = parse_bound(q.to.as_deref(), "to")?.unwrap_or_else(clock::now);
    let from = parse_bound(q.from.as_deref(), "from")?.unwrap_or(to - DEFAULT_HISTORY_WINDOW_MS);
    if from > to {
        return Err(AppError::BadRequest(
            "期間の指定が逆になっています。".to_owned(),
        ));
    }
    let limit = q
        .limit
        .unwrap_or(DEFAULT_HISTORY_LIMIT)
        .clamp(1, MAX_HISTORY_LIMIT);

    // device_id は任意指定なので、条件を SQL に分岐させず
    // 「NULL なら全件」を 1 本のクエリで表現する。
    let rows: Vec<HistoryRow> = sqlx::query_as(
        "SELECT device_id, lat, lng, accuracy, recorded_at, battery_level \
         FROM location_events \
         WHERE family_id = ?1 AND recorded_at >= ?2 AND recorded_at <= ?3 \
           AND (?4 IS NULL OR device_id = ?4) \
         ORDER BY recorded_at ASC \
         LIMIT ?5",
    )
    .bind(&family_id)
    .bind(from)
    .bind(to)
    .bind(q.device_id.as_deref())
    .bind(limit)
    .fetch_all(&state.db)
    .await?;

    let truncated = rows.len() as i64 == limit;
    let events = rows
        .into_iter()
        .map(|r| HistoryEvent {
            device_id: r.device_id,
            lat: r.lat,
            lng: r.lng,
            accuracy: r.accuracy,
            recorded_at: clock::to_rfc3339(r.recorded_at),
            battery_level: r.battery_level,
        })
        .collect();

    Ok(Json(HistoryResponse {
        family_id,
        from: clock::to_rfc3339(from),
        to: clock::to_rfc3339(to),
        truncated,
        events,
    }))
}

fn parse_bound(raw: Option<&str>, name: &str) -> Result<Option<Millis>, AppError> {
    match raw.map(str::trim).filter(|s| !s.is_empty()) {
        None => Ok(None),
        Some(s) => clock::parse_rfc3339(s).map(Some).ok_or_else(|| {
            AppError::BadRequest(format!("{name} は RFC 3339 形式で指定してください。"))
        }),
    }
}

// --------------------------------------------------------------- invites

#[derive(Debug, Serialize)]
pub struct InviteResponse {
    pub code: String,
    pub expires_at: String,
}

/// `POST /api/v1/families/{family_id}/invites`
///
/// 親端末をペアリングするための使い切りコードを発行する。
/// CLAUDE.md §3.2 には無いが、これが無いと登録経路が存在しないため足している。
pub async fn create_invite(
    State(state): State<AppState>,
    auth: ChildAuth,
    Path(family_id): Path<String>,
) -> AppResult<(StatusCode, Json<InviteResponse>)> {
    auth.scope(&family_id)?;

    let now = clock::now();
    let expires_at = now + state.config.invite_ttl.as_millis() as i64;

    // 生成したコードが既存と衝突する確率は極めて低いが、
    // 一意制約違反で発行が失敗するのは避けたいので数回だけ引き直す。
    for _ in 0..5 {
        let code = generate_invite_code()?;
        let result = sqlx::query(
            "INSERT OR IGNORE INTO invite_codes \
             (code, family_id, created_by, created_at, expires_at) \
             VALUES (?1, ?2, ?3, ?4, ?5)",
        )
        .bind(&code)
        .bind(&family_id)
        .bind(&auth.child_id)
        .bind(now)
        .bind(expires_at)
        .execute(&state.db)
        .await?;

        if result.rows_affected() == 1 {
            tracing::info!(%family_id, child_id = %auth.child_id, "招待コードを発行しました");
            return Ok((
                StatusCode::CREATED,
                Json(InviteResponse {
                    code,
                    expires_at: clock::to_rfc3339(expires_at),
                }),
            ));
        }
    }

    Err(AppError::Internal(anyhow::anyhow!(
        "招待コードの生成に繰り返し失敗しました"
    )))
}

// --------------------------------------------------------------- devices

/// `POST /api/v1/families/{family_id}/devices/{device_id}/revoke`
///
/// 端末を紛失したときに、その端末のトークンだけを無効化する。
/// アプリ側の「接続を解除する」は端末内のデータを消すだけでトークンは
/// 生きたままなので、サーバー側にもこの操作が要る。
/// 既に受け取った位置履歴は保持期間に従って自然に消える。
pub async fn revoke_device(
    State(state): State<AppState>,
    auth: ChildAuth,
    Path((family_id, device_id)): Path<(String, String)>,
) -> AppResult<StatusCode> {
    auth.scope(&family_id)?;

    let result = sqlx::query(
        "UPDATE parent_devices SET revoked_at = ?1 \
         WHERE id = ?2 AND family_id = ?3 AND revoked_at IS NULL",
    )
    .bind(clock::now())
    .bind(&device_id)
    .bind(&family_id)
    .execute(&state.db)
    .await?;

    if result.rows_affected() == 0 {
        return Err(AppError::NotFound);
    }
    tracing::info!(%device_id, %family_id, "端末を無効化しました");
    Ok(StatusCode::NO_CONTENT)
}
