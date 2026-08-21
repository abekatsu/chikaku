use axum::extract::FromRequestParts;
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;

use crate::auth::token::hash_secret;
use crate::clock;
use crate::error::AppError;
use crate::state::AppState;

fn bearer(parts: &Parts) -> Result<&str, AppError> {
    parts
        .headers
        .get(AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        // スキーム名は大文字小文字を区別しない (RFC 7235)。
        .and_then(|v| {
            let (scheme, token) = v.split_once(' ')?;
            scheme
                .eq_ignore_ascii_case("bearer")
                .then_some(token.trim())
        })
        .filter(|t| !t.is_empty())
        .ok_or(AppError::Unauthorized)
}

/// 親端末の認証結果。`POST /api/v1/location` で使う。
#[derive(Debug, Clone)]
pub struct DeviceAuth {
    pub device_id: String,
    pub family_id: String,
}

impl FromRequestParts<AppState> for DeviceAuth {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let token_hash = hash_secret(bearer(parts)?);
        let row: Option<(String, String)> = sqlx::query_as(
            "SELECT id, family_id FROM parent_devices \
             WHERE token_hash = ?1 AND revoked_at IS NULL",
        )
        .bind(&token_hash)
        .fetch_optional(&state.db)
        .await?;

        let (device_id, family_id) = row.ok_or(AppError::Unauthorized)?;
        Ok(Self {
            device_id,
            family_id,
        })
    }
}

/// 子アカウント（ダッシュボード利用者）の認証結果。
#[derive(Debug, Clone)]
pub struct ChildAuth {
    pub child_id: String,
    pub family_id: String,
    /// ログアウトで「今使っているセッションだけ」を消せるように持ち回る。
    pub token_hash: String,
}

impl ChildAuth {
    /// パスに現れた family_id を、このセッションが見てよいものか検査する。
    ///
    /// 他家族の ID を指されたとき 403 ではなく 404 を返すのは、
    /// 「その family_id は実在する」という事実自体を漏らさないため (CLAUDE.md §5)。
    pub fn scope(&self, family_id: &str) -> Result<(), AppError> {
        if self.family_id == family_id {
            Ok(())
        } else {
            Err(AppError::NotFound)
        }
    }
}

impl FromRequestParts<AppState> for ChildAuth {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let token_hash = hash_secret(bearer(parts)?);
        let row: Option<(String, String)> = sqlx::query_as(
            "SELECT a.id, a.family_id FROM child_sessions s \
             JOIN children_accounts a ON a.id = s.child_id \
             WHERE s.token_hash = ?1 AND s.expires_at > ?2",
        )
        .bind(&token_hash)
        .bind(clock::now())
        .fetch_optional(&state.db)
        .await?;

        let (child_id, family_id) = row.ok_or(AppError::Unauthorized)?;
        Ok(Self {
            child_id,
            family_id,
            token_hash,
        })
    }
}
