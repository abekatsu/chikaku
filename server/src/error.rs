use axum::Json;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::Serialize;

/// Android 側の `ErrorResponse` と同じ形。
///
/// `error` は機械可読なコード、`message` は画面にそのまま出る日本語。
/// アプリは 4xx のとき `message` を高齢のユーザーに直接見せる
/// (MainViewModel の ClientError 分岐) ため、専門用語を出さない。
#[derive(Debug, Serialize)]
pub struct ErrorBody {
    pub error: &'static str,
    pub message: String,
}

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    /// 招待コードが不正・期限切れ・使用済み。
    /// アプリは 401 を受けると自前の日本語文言を出して再入力を促す。
    #[error("invalid invite code")]
    InvalidInviteCode,

    /// Bearer トークンが無い・不正・失効。アプリはペアリングをやり直す。
    #[error("unauthorized")]
    Unauthorized,

    /// 認証は通ったが、その資格情報では触れない家族のデータ。
    /// 存在の有無を漏らさないため 404 として返す (CLAUDE.md §5)。
    #[error("not found")]
    NotFound,

    #[error("bad request: {0}")]
    BadRequest(String),

    #[error(transparent)]
    Database(#[from] sqlx::Error),

    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

impl AppError {
    fn parts(&self) -> (StatusCode, &'static str, String) {
        match self {
            Self::InvalidInviteCode => (
                StatusCode::UNAUTHORIZED,
                "invalid_invite_code",
                "招待コードが正しくないか、期限が切れています。".to_owned(),
            ),
            Self::Unauthorized => (
                StatusCode::UNAUTHORIZED,
                "unauthorized",
                "認証できませんでした。もう一度つなぎ直してください。".to_owned(),
            ),
            Self::NotFound => (
                StatusCode::NOT_FOUND,
                "not_found",
                "見つかりませんでした。".to_owned(),
            ),
            Self::BadRequest(m) => (StatusCode::BAD_REQUEST, "bad_request", m.clone()),
            Self::Database(_) | Self::Internal(_) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                "サーバー側で問題が起きました。しばらくしてからお試しください。".to_owned(),
            ),
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, error, message) = self.parts();
        // 5xx の原因はクライアントに返さずログにだけ残す。
        if status.is_server_error() {
            tracing::error!(error = ?self, "request failed");
        }
        (status, Json(ErrorBody { error, message })).into_response()
    }
}

pub type AppResult<T> = Result<T, AppError>;
