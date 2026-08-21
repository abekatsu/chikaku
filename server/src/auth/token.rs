use anyhow::{Context, Result};
use sha2::{Digest, Sha256};

/// 招待コードで使う文字集合。
/// 高齢の利用者が紙やメモから読み取って手入力するため、
/// 見間違えやすい 0 1 2 と B I L O S Z を最初から除いた 27 文字。
const INVITE_ALPHABET: &[u8] = b"3456789ACDEFGHJKMNPQRTUVWXY";

/// 秘密トークン (device_token / セッショントークン) の生成。
/// 32 バイトの OS 乱数を hex 化して返す。
pub fn generate_secret() -> Result<String> {
    let mut buf = [0u8; 32];
    getrandom::fill(&mut buf).context("乱数の取得に失敗しました")?;
    Ok(hex::encode(buf))
}

/// 8 桁の招待コード。27^8 ≒ 2.8e11 通り。
/// 総当たりされないよう有効期限を短く保つ運用と併せて使う。
pub fn generate_invite_code() -> Result<String> {
    let mut buf = [0u8; 8];
    getrandom::fill(&mut buf).context("乱数の取得に失敗しました")?;
    // アルファベットの長さが 2 の冪ではないため剰余に僅かな偏りが出るが、
    // 期限付き招待コードの用途では実害がない範囲として許容する。
    Ok(buf
        .iter()
        .map(|b| INVITE_ALPHABET[*b as usize % INVITE_ALPHABET.len()] as char)
        .collect())
}

/// 入力された招待コードの表記ゆれを吸収する。
/// 大文字小文字と、読みやすさのために入れられた空白・ハイフンを落とす。
pub fn normalize_invite_code(input: &str) -> String {
    input
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .collect()
}

/// トークンは原文を保存せず、この SHA-256 hex だけを DB に持つ。
/// 入力が 32 バイトの一様乱数なので、パスワードと違い伸長は不要。
pub fn hash_secret(secret: &str) -> String {
    hex::encode(Sha256::digest(secret.as_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invite_code_is_unambiguous_and_fixed_length() {
        let code = generate_invite_code().unwrap();
        assert_eq!(code.len(), 8);
        assert!(
            code.chars().all(|c| INVITE_ALPHABET.contains(&(c as u8))),
            "見間違えやすい文字が混ざっている: {code}"
        );
    }

    #[test]
    fn normalize_accepts_how_people_actually_type_it() {
        assert_eq!(normalize_invite_code("a3c4-d5e6"), "A3C4D5E6");
        assert_eq!(normalize_invite_code(" A3C4 D5E6 "), "A3C4D5E6");
    }

    #[test]
    fn secrets_differ_and_hash_stably() {
        let a = generate_secret().unwrap();
        let b = generate_secret().unwrap();
        assert_ne!(a, b);
        assert_eq!(a.len(), 64);
        assert_eq!(hash_secret(&a), hash_secret(&a));
        assert_ne!(hash_secret(&a), hash_secret(&b));
    }
}
