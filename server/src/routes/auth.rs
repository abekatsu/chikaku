use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use serde::{Deserialize, Serialize};

use crate::auth::extract::ChildAuth;
use crate::auth::password;
use crate::auth::token::{generate_secret, hash_secret};
use crate::clock;
use crate::error::{AppError, AppResult};
use crate::state::AppState;

/// 実在しないメールアドレスでも検証と同じだけ時間を使うためのダミー。
/// これが無いと応答時間の差でアカウントの存在を探れてしまう。
static DUMMY_PHC: std::sync::OnceLock<String> = std::sync::OnceLock::new();

fn dummy_phc() -> &'static str {
    DUMMY_PHC.get_or_init(|| {
        password::hash("dummy password for constant-time login")
            .expect("起動時のダミーハッシュ生成に失敗しました")
    })
}

#[derive(Debug, Deserialize)]
pub struct LoginRequest {
    pub email: String,
    pub password: String,
}

#[derive(Debug, Serialize)]
pub struct LoginResponse {
    pub token: String,
    pub expires_at: String,
    pub child: ChildProfile,
}

#[derive(Debug, Serialize)]
pub struct ChildProfile {
    pub id: String,
    pub family_id: String,
    pub email: String,
    pub display_name: String,
}

#[derive(sqlx::FromRow)]
struct ChildRow {
    id: String,
    family_id: String,
    email: String,
    display_name: String,
    password_hash: String,
}

/// `POST /api/v1/auth/login`
pub async fn login(
    State(state): State<AppState>,
    Json(req): Json<LoginRequest>,
) -> AppResult<Json<LoginResponse>> {
    let email = req.email.trim().to_lowercase();

    let row: Option<ChildRow> = sqlx::query_as(
        "SELECT id, family_id, email, display_name, password_hash \
         FROM children_accounts WHERE lower(email) = ?1",
    )
    .bind(&email)
    .fetch_optional(&state.db)
    .await?;

    // アカウントの有無で分岐せず、常に 1 回ハッシュ検証を通す。
    let phc = row
        .as_ref()
        .map_or(dummy_phc(), |r| r.password_hash.as_str());
    let ok = password::verify(&req.password, phc);

    let Some(child) = row.filter(|_| ok) else {
        // 「メールが違う」「パスワードが違う」を区別せず同じ応答を返す。
        return Err(AppError::Unauthorized);
    };

    let token = generate_secret()?;
    let now = clock::now();
    let expires_at = now + state.config.session_ttl.as_millis() as i64;

    sqlx::query(
        "INSERT INTO child_sessions (token_hash, child_id, created_at, expires_at) \
         VALUES (?1, ?2, ?3, ?4)",
    )
    .bind(hash_secret(&token))
    .bind(&child.id)
    .bind(now)
    .bind(expires_at)
    .execute(&state.db)
    .await?;

    tracing::info!(child_id = %child.id, "ログインしました");
    Ok(Json(LoginResponse {
        token,
        expires_at: clock::to_rfc3339(expires_at),
        child: ChildProfile {
            id: child.id,
            family_id: child.family_id,
            email: child.email,
            display_name: child.display_name,
        },
    }))
}

/// `POST /api/v1/auth/logout` — 今提示しているセッションだけを失効させる。
/// 他の端末でのログインは残す。
pub async fn logout(State(state): State<AppState>, auth: ChildAuth) -> AppResult<StatusCode> {
    sqlx::query("DELETE FROM child_sessions WHERE token_hash = ?1")
        .bind(&auth.token_hash)
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

/// `GET /api/v1/auth/me` — トークンが生きているかの確認とプロフィール取得。
/// ダッシュボードが起動時にセッションの有効性を判断するために使う。
pub async fn me(State(state): State<AppState>, auth: ChildAuth) -> AppResult<Json<ChildProfile>> {
    let row: Option<(String, String)> =
        sqlx::query_as("SELECT email, display_name FROM children_accounts WHERE id = ?1")
            .bind(&auth.child_id)
            .fetch_optional(&state.db)
            .await?;
    let (email, display_name) = row.ok_or(AppError::Unauthorized)?;
    Ok(Json(ChildProfile {
        id: auth.child_id,
        family_id: auth.family_id,
        email,
        display_name,
    }))
}
