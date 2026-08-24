use serde::Serialize;
use worker::{Response, Result as WorkerResult};

/// Android 側の `ErrorResponse` と同じ形。
///
/// `error` は機械可読なコード、`message` は画面にそのまま出る日本語。
/// アプリは 4xx のとき `message` を高齢のユーザーに直接見せる
/// (MainViewModel の ClientError 分岐) ため、専門用語を出さない。
#[derive(Debug, Serialize)]
struct ErrorBody {
    error: &'static str,
    message: String,
}

#[derive(Debug)]
pub enum AppError {
    /// 招待コードが不正・期限切れ・使用済み。
    /// アプリは 401 を受けると自前の日本語文言を出して再入力を促す。
    InvalidInviteCode,

    /// 端末トークンまたは Access JWT が無い・不正・失効。
    Unauthorized,

    /// Access の認証は通ったが、`children_accounts` に無いメールアドレス。
    /// Access のポリシーとこの表の二重で絞る (ADR-3)。
    NotProvisioned,

    /// 認証は通ったが、その資格情報では触れない家族のデータ。
    /// 存在の有無を漏らさないため 404 として返す (CLAUDE.md §5)。
    NotFound,

    BadRequest(String),

    /// 短時間に試行が集中した。招待コードの総当たりを想定する。
    /// Android は 429 を一時的な失敗として扱い、再試行に回す
    /// (`ApiClient.execute` の 408/429 分岐)。
    TooManyRequests,

    /// 内部エラー。原因はログにだけ残しクライアントには返さない。
    Internal(String),
}

impl AppError {
    fn parts(&self) -> (u16, &'static str, String) {
        match self {
            Self::InvalidInviteCode => (
                401,
                "invalid_invite_code",
                "招待コードが正しくないか、期限が切れています。".to_owned(),
            ),
            Self::Unauthorized => (
                401,
                "unauthorized",
                "認証できませんでした。もう一度つなぎ直してください。".to_owned(),
            ),
            Self::NotProvisioned => (
                403,
                "not_provisioned",
                "このアカウントは登録されていません。".to_owned(),
            ),
            Self::NotFound => (404, "not_found", "見つかりませんでした。".to_owned()),
            Self::BadRequest(m) => (400, "bad_request", m.clone()),
            Self::TooManyRequests => (
                429,
                "too_many_requests",
                "試行が多すぎます。しばらく待ってからお試しください。".to_owned(),
            ),
            Self::Internal(_) => (
                500,
                "internal_error",
                "サーバー側で問題が起きました。しばらくしてからお試しください。".to_owned(),
            ),
        }
    }

    pub fn into_response(self) -> WorkerResult<Response> {
        let (status, error, message) = self.parts();
        if let Self::Internal(detail) = &self {
            worker::console_error!("internal error: {detail}");
        }
        Ok(Response::from_json(&ErrorBody { error, message })?.with_status(status))
    }
}

impl From<worker::Error> for AppError {
    fn from(e: worker::Error) -> Self {
        Self::Internal(e.to_string())
    }
}

pub type AppResult<T> = std::result::Result<T, AppError>;

/// ハンドラの結果を Workers の戻り値に変換する。
/// エラーも必ず JSON 本文を持つ応答にして、クライアントが常に同じ形を読めるようにする。
pub fn finish(result: AppResult<Response>) -> WorkerResult<Response> {
    match result {
        Ok(res) => Ok(res),
        Err(e) => e.into_response(),
    }
}
