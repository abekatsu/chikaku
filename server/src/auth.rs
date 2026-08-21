//! リクエストから「誰か」を確定させる。
//!
//! 主体は2種類あり、経路が完全に分かれている (ADR-3)。
//! - 親端末: `Authorization: Bearer <device_token>`
//! - 子アカウント: Cloudflare Access の JWT (`Cf-Access-Jwt-Assertion`)
//!
//! 片方の資格情報でもう片方の API を叩くことは、参照する表が違うため構造上できない。

use serde::Deserialize;
use worker::{D1Database, Request};

use crate::access;
use crate::config::Config;
use crate::db;
use crate::error::{AppError, AppResult};
use crate::token;

/// 親端末の認証結果。
#[derive(Debug, Clone)]
pub struct DeviceAuth {
    pub device_id: String,
    pub family_id: String,
}

#[derive(Deserialize)]
struct DeviceRow {
    id: String,
    family_id: String,
}

/// `Authorization: Bearer <device_token>` を検証する。
/// 失効した端末 (`revoked_at` が入っている) は照合条件で弾かれるため、
/// 無効化は次のリクエストから即座に効く。
pub async fn device(req: &Request, database: &D1Database) -> AppResult<DeviceAuth> {
    let raw = token::bearer(req.headers().get("Authorization").ok().flatten())?;
    let row: Option<DeviceRow> = db::first(
        database,
        "SELECT id, family_id FROM parent_devices \
         WHERE token_hash = ?1 AND revoked_at IS NULL",
        &[db::text(&token::hash_secret(&raw))],
    )
    .await?;

    let row = row.ok_or(AppError::Unauthorized)?;
    Ok(DeviceAuth {
        device_id: row.id,
        family_id: row.family_id,
    })
}

/// 子アカウント（ダッシュボード利用者）の認証結果。
#[derive(Debug, Clone)]
pub struct ChildAuth {
    pub child_id: String,
    pub family_id: String,
    pub email: String,
    pub display_name: String,
}

#[derive(Deserialize)]
struct ChildRow {
    id: String,
    family_id: String,
    email: String,
    display_name: String,
}

impl ChildAuth {
    /// パスに現れた family_id を、この人が見てよいものか検査する。
    ///
    /// 他家族の ID を指されたとき 403 ではなく 404 を返すのは、
    /// 「その family_id は実在する」という事実自体を漏らさないため (CLAUDE.md §5)。
    pub fn scope(&self, family_id: &str) -> AppResult<()> {
        if self.family_id == family_id {
            Ok(())
        } else {
            Err(AppError::NotFound)
        }
    }
}

/// Access の JWT を検証し、そのメールアドレスで子アカウントを引く。
///
/// Access のポリシーを通っていても `children_accounts` に無いメールは拒否する。
/// Access 側の許可リストとこの表の二重で絞ることで、
/// Access の設定を広げてしまった場合の影響を抑える (ADR-3)。
pub async fn child(req: &Request, database: &D1Database, config: &Config) -> AppResult<ChildAuth> {
    let jwt = req
        .headers()
        .get("Cf-Access-Jwt-Assertion")
        .ok()
        .flatten()
        .ok_or(AppError::Unauthorized)?;

    let identity = access::verify(&jwt, &config.team_domain, &config.policy_aud).await?;

    let row: Option<ChildRow> = db::first(
        database,
        "SELECT id, family_id, email, display_name FROM children_accounts \
         WHERE lower(email) = ?1",
        &[db::text(&identity.email)],
    )
    .await?;

    let row = row.ok_or(AppError::NotProvisioned)?;
    Ok(ChildAuth {
        child_id: row.id,
        family_id: row.family_id,
        email: row.email,
        display_name: row.display_name,
    })
}
