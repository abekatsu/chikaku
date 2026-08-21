//! 高齢者見守り位置情報システムのバックエンド。
//!
//! Cloudflare Workers 上で動き、同じオリジンでダッシュボードの静的ファイルも配る。
//! 構成の判断は `docs/architecture-decisions.md`、認証は `docs/authentication.md` を参照。

mod access;
mod auth;
mod clock;
mod config;
mod db;
mod error;
mod retention;
mod routes;
mod token;
mod validate;

use worker::*;

use config::Config;
use error::{AppError, AppResult, finish};

#[event(fetch)]
async fn fetch(req: Request, env: Env, _ctx: Context) -> Result<Response> {
    console_error_panic_hook::set_once();
    finish(route(req, env).await)
}

/// Cron Trigger から呼ばれる。保持期間を過ぎたデータを消す。
#[event(scheduled)]
async fn scheduled(_event: ScheduledEvent, env: Env, _ctx: ScheduleContext) {
    let result = async {
        let config = Config::from_env(&env)?;
        let database = env.d1("DB").map_err(AppError::from)?;
        retention::sweep(&database, config.retention_ms).await
    }
    .await;

    match result {
        Ok(swept) => console_log!(
            "掃除しました 位置履歴={} 招待コード={}",
            swept.events,
            swept.invites
        ),
        // 失敗しても次の実行に賭ける。
        Err(e) => console_error!("掃除に失敗しました: {e:?}"),
    }
}

async fn route(mut req: Request, env: Env) -> AppResult<Response> {
    let url = req.url().map_err(AppError::from)?;
    let path = url.path().to_owned();
    let method = req.method();

    // 静的ファイルは Workers Static Assets が配るため、Worker には
    // /api/* しか回ってこない (wrangler.jsonc の run_worker_first)。
    let segments: Vec<&str> = path.trim_matches('/').split('/').collect();
    let rest = match segments.as_slice() {
        ["api", "v1", rest @ ..] => rest,
        _ => return Err(AppError::NotFound),
    };

    // 認証を要らない経路を先に処理する。ここに足すときは
    // Access のバイパス設定と必ず対で見直すこと (ADR-3)。
    if let (Method::Get, ["healthz"]) = (&method, rest) {
        return Ok(Response::from_json(&serde_json::json!({ "status": "ok" }))?);
    }

    let config = Config::from_env(&env)?;
    let database = env.d1("DB").map_err(AppError::from)?;

    match (&method, rest) {
        // ---- 親端末 (Cloudflare Access のバイパス対象) ----
        (Method::Post, ["devices", "register"]) => {
            let body = req.json().await.map_err(|_| {
                AppError::BadRequest("リクエストの形式が正しくありません。".to_owned())
            })?;
            routes::devices::register(&database, body).await
        }

        (Method::Post, ["location"]) => {
            let auth = auth::device(&req, &database).await?;
            let body = req.json().await.map_err(|_| {
                AppError::BadRequest("リクエストの形式が正しくありません。".to_owned())
            })?;
            routes::location::create(&database, &auth, config.retention_ms, body).await
        }

        // ---- 子アカウント (Cloudflare Access の内側) ----
        (Method::Get, ["me"]) => {
            let auth = auth::child(&req, &database, &config).await?;
            routes::families::me(&auth).await
        }

        (Method::Get, ["families", family_id, "latest"]) => {
            let auth = auth::child(&req, &database, &config).await?;
            routes::families::latest(&database, &auth, family_id).await
        }

        (Method::Get, ["families", family_id, "history"]) => {
            let auth = auth::child(&req, &database, &config).await?;
            routes::families::history(&database, &auth, family_id, &url).await
        }

        (Method::Post, ["families", family_id, "invites"]) => {
            let auth = auth::child(&req, &database, &config).await?;
            routes::families::create_invite(&database, &auth, family_id, config.invite_ttl_ms).await
        }

        (Method::Post, ["families", family_id, "devices", device_id, "revoke"]) => {
            let auth = auth::child(&req, &database, &config).await?;
            routes::families::revoke_device(&database, &auth, family_id, device_id).await
        }

        _ => Err(AppError::NotFound),
    }
}
