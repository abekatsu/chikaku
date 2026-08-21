use crate::error::AppError;

pub const MAX_NAME_LEN: usize = 80;
pub const MAX_EMAIL_LEN: usize = 254;

/// 空でない文字列を取り出し、長すぎる入力を切り詰める。
/// エラー文言はそのまま端末の画面に出るので日本語で受け取る。
pub fn require_text(raw: &str, max: usize, empty_message: &str) -> Result<String, AppError> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(AppError::BadRequest(empty_message.to_owned()));
    }
    Ok(trimmed.chars().take(max).collect())
}
