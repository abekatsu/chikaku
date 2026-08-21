use crate::error::AppError;

pub const MAX_NAME_LEN: usize = 80;

/// 空でない文字列を取り出し、長すぎる入力を切り詰める。
/// エラー文言はそのまま端末の画面に出るので日本語で受け取る。
pub fn require_text(raw: &str, max: usize, empty_message: &str) -> Result<String, AppError> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(AppError::BadRequest(empty_message.to_owned()));
    }
    Ok(trimmed.chars().take(max).collect())
}

/// 長さだけ制限して切り詰める。空を許す補助情報向け。
pub fn clamp_text(raw: &str, max: usize) -> String {
    raw.trim().chars().take(max).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_whitespace_only() {
        assert!(require_text("   ", 10, "名前を入力してください").is_err());
    }

    #[test]
    fn truncates_by_characters_not_bytes() {
        // 日本語でもバイト境界で壊れないこと。
        assert_eq!(require_text("あいうえお", 3, "x").unwrap(), "あいう");
    }
}
