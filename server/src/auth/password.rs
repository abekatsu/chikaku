use anyhow::{Context, Result, anyhow};
use argon2::Argon2;
use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};

/// 子アカウントのパスワードを Argon2id でハッシュ化する。
/// 戻り値の PHC 文字列にソルトとパラメータが含まれるので、DB にはこれだけ入れる。
pub fn hash(password: &str) -> Result<String> {
    let mut salt_bytes = [0u8; 16];
    getrandom::fill(&mut salt_bytes).context("ソルトの生成に失敗しました")?;
    let salt = SaltString::encode_b64(&salt_bytes).map_err(|e| anyhow!("{e}"))?;
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|e| anyhow!("パスワードのハッシュ化に失敗しました: {e}"))
}

/// 検証。ハッシュが壊れている場合も「不一致」に倒し、
/// 呼び出し側が理由の違いで応答を変えてしまわないようにする。
pub fn verify(password: &str, phc: &str) -> bool {
    match PasswordHash::new(phc) {
        Ok(parsed) => Argon2::default()
            .verify_password(password.as_bytes(), &parsed)
            .is_ok(),
        Err(e) => {
            tracing::error!(%e, "保存されているパスワードハッシュを解釈できません");
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let phc = hash("correct horse battery staple").unwrap();
        assert!(verify("correct horse battery staple", &phc));
        assert!(!verify("wrong password", &phc));
    }

    #[test]
    fn same_password_gets_different_hashes() {
        assert_ne!(hash("same").unwrap(), hash("same").unwrap());
    }

    #[test]
    fn broken_hash_never_verifies() {
        assert!(!verify("anything", "not-a-phc-string"));
    }
}
