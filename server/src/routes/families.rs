use serde::{Deserialize, Serialize};
use worker::{D1Database, Response, Url};

use crate::auth::ChildAuth;
use crate::clock::{self, Millis};
use crate::db;
use crate::error::{AppError, AppResult};
use crate::routes::DeviceHealth;
use crate::token;

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
    /// 端末設定の健康状態 (Issue #4)。報告に対応する前のアプリでは null。
    /// **null は「問題なし」ではなく「分からない」。** 画面でも区別して出す。
    pub health: Option<DeviceHealthReport>,
}

#[derive(Debug, Serialize)]
pub struct DeviceHealthReport {
    #[serde(flatten)]
    pub health: DeviceHealth,
    /// この状態を受け取った時刻。古ければ端末が長く沈黙していることを意味する。
    pub reported_at: String,
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
    /// 測位の出どころ (Issue #13)。`satellite` / `network` / `unknown`。
    /// **null は「衛星測位だった」ではなく「報告が無い」。**
    /// 判定の根拠になった生の値は DB にあるが、画面では使わないので返さない。
    pub source: Option<String>,
}

#[derive(Deserialize)]
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
    source_kind: Option<String>,
    battery_unrestricted: Option<i64>,
    notifications_enabled: Option<i64>,
    background_location: Option<i64>,
    health_reported_at: Option<Millis>,
}

/// `GET /api/v1/families/{family_id}/latest`
pub async fn latest(
    database: &D1Database,
    auth: &ChildAuth,
    family_id: &str,
) -> AppResult<Response> {
    auth.scope(family_id)?;

    let rows: Vec<LatestRow> = db::all(
        database,
        "SELECT d.id, d.device_name, d.device_model, d.last_seen_at, \
                d.battery_unrestricted, d.notifications_enabled, \
                d.background_location, d.health_reported_at, \
                e.lat, e.lng, e.accuracy, e.recorded_at, e.received_at, e.battery_level, \
                e.source_kind \
         FROM parent_devices d \
         LEFT JOIN location_events e ON e.id = ( \
             SELECT id FROM location_events \
             WHERE device_id = d.id ORDER BY recorded_at DESC LIMIT 1 \
         ) \
         WHERE d.family_id = ?1 AND d.revoked_at IS NULL \
         ORDER BY d.created_at",
        &[db::text(family_id)],
    )
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
                        source: r.source_kind,
                    })
                }
                _ => None,
            },
            // 4 列は必ず揃って書かれるが、片方だけ NULL の行を「問題あり」と
            // 誤って読まないよう、全部揃っているときだけ報告として扱う。
            health: match (
                r.health_reported_at,
                r.battery_unrestricted,
                r.notifications_enabled,
                r.background_location,
            ) {
                (Some(at), Some(battery), Some(notifications), Some(background)) => {
                    Some(DeviceHealthReport {
                        health: DeviceHealth {
                            battery_unrestricted: battery != 0,
                            notifications_enabled: notifications != 0,
                            background_location: background != 0,
                        },
                        reported_at: clock::to_rfc3339(at),
                    })
                }
                _ => None,
            },
        })
        .collect();

    Ok(Response::from_json(&LatestResponse {
        family_id: family_id.to_owned(),
        devices,
    })?)
}

// --------------------------------------------------------------- history

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
    /// 測位の出どころ (Issue #13)。**null は「報告が無い」。**
    pub source: Option<String>,
}

#[derive(Deserialize)]
struct HistoryRow {
    device_id: String,
    lat: f64,
    lng: f64,
    accuracy: f64,
    recorded_at: Millis,
    battery_level: i64,
    source_kind: Option<String>,
}

/// `GET /api/v1/families/{family_id}/history?from=&to=&device_id=&limit=`
///
/// 期間を省略すると直近 24 時間。時刻は RFC 3339 で受け取る。
pub async fn history(
    database: &D1Database,
    auth: &ChildAuth,
    family_id: &str,
    url: &Url,
) -> AppResult<Response> {
    auth.scope(family_id)?;

    let q = |key: &str| -> Option<String> {
        url.query_pairs()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.into_owned())
            .filter(|v| !v.trim().is_empty())
    };

    let to = parse_bound(q("to").as_deref(), "to")?.unwrap_or_else(clock::now);
    let from = parse_bound(q("from").as_deref(), "from")?.unwrap_or(to - DEFAULT_HISTORY_WINDOW_MS);
    if from > to {
        return Err(AppError::BadRequest(
            "期間の指定が逆になっています。".to_owned(),
        ));
    }
    let limit = q("limit")
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(DEFAULT_HISTORY_LIMIT)
        .clamp(1, MAX_HISTORY_LIMIT);
    let device_id = q("device_id");

    // device_id は任意指定なので、条件を SQL に分岐させず
    // 「NULL なら全件」を 1 本のクエリで表現する。
    let rows: Vec<HistoryRow> = db::all(
        database,
        "SELECT device_id, lat, lng, accuracy, recorded_at, battery_level, source_kind \
         FROM location_events \
         WHERE family_id = ?1 AND recorded_at >= ?2 AND recorded_at <= ?3 \
           AND (?4 IS NULL OR device_id = ?4) \
         ORDER BY recorded_at ASC \
         LIMIT ?5",
        &[
            db::text(family_id),
            db::num(from),
            db::num(to),
            db::opt_text(device_id.as_deref()),
            db::num(limit),
        ],
    )
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
            source: r.source_kind,
        })
        .collect();

    Ok(Response::from_json(&HistoryResponse {
        family_id: family_id.to_owned(),
        from: clock::to_rfc3339(from),
        to: clock::to_rfc3339(to),
        truncated,
        events,
    })?)
}

fn parse_bound(raw: Option<&str>, name: &str) -> AppResult<Option<Millis>> {
    match raw {
        None => Ok(None),
        Some(s) => clock::parse_rfc3339(s.trim()).map(Some).ok_or_else(|| {
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
    database: &D1Database,
    auth: &ChildAuth,
    family_id: &str,
    invite_ttl_ms: i64,
) -> AppResult<Response> {
    auth.scope(family_id)?;

    let now = clock::now();
    let expires_at = now + invite_ttl_ms;

    // 生成したコードが既存と衝突する確率は極めて低いが、
    // 一意制約違反で発行が失敗するのは避けたいので数回だけ引き直す。
    for _ in 0..5 {
        let code = token::generate_invite_code()?;
        let inserted = db::run(
            database,
            "INSERT OR IGNORE INTO invite_codes \
             (code, family_id, created_by, created_at, expires_at) \
             VALUES (?1, ?2, ?3, ?4, ?5)",
            &[
                db::text(&code),
                db::text(family_id),
                db::text(&auth.child_id),
                db::num(now),
                db::num(expires_at),
            ],
        )
        .await?;

        if inserted == 1 {
            worker::console_log!(
                "招待コードを発行 family_id={family_id} child_id={}",
                auth.child_id
            );
            return Ok(Response::from_json(&InviteResponse {
                code,
                expires_at: clock::to_rfc3339(expires_at),
            })?
            .with_status(201));
        }
    }

    Err(AppError::Internal(
        "招待コードの生成に繰り返し失敗しました".into(),
    ))
}

// --------------------------------------------------------------- devices

/// `POST /api/v1/families/{family_id}/devices/{device_id}/revoke`
///
/// 端末を紛失したときに、その端末のトークンだけを無効化する。
/// アプリ側の「接続を解除する」は端末内のデータを消すだけでトークンは
/// 生きたままなので、サーバー側にもこの操作が要る。
/// 既に受け取った位置履歴は保持期間に従って自然に消える。
pub async fn revoke_device(
    database: &D1Database,
    auth: &ChildAuth,
    family_id: &str,
    device_id: &str,
) -> AppResult<Response> {
    auth.scope(family_id)?;

    let updated = db::run(
        database,
        "UPDATE parent_devices SET revoked_at = ?1 \
         WHERE id = ?2 AND family_id = ?3 AND revoked_at IS NULL",
        &[
            db::num(clock::now()),
            db::text(device_id),
            db::text(family_id),
        ],
    )
    .await?;

    if updated == 0 {
        return Err(AppError::NotFound);
    }
    worker::console_log!("端末を無効化 device_id={device_id} family_id={family_id}");
    Ok(Response::empty()?.with_status(204))
}

// ------------------------------------------------------------------ me

#[derive(Debug, Serialize)]
pub struct Profile {
    pub child_id: String,
    pub family_id: String,
    pub email: String,
    pub display_name: String,
}

/// `GET /api/v1/me`
///
/// Access を通過した本人が誰で、どの家族に属するかを返す。
/// ダッシュボードは起動時にこれを呼んで family_id を得る。
pub async fn me(auth: &ChildAuth) -> AppResult<Response> {
    Ok(Response::from_json(&Profile {
        child_id: auth.child_id.clone(),
        family_id: auth.family_id.clone(),
        email: auth.email.clone(),
        display_name: auth.display_name.clone(),
    })?)
}
