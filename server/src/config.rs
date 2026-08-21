use worker::Env;

use crate::error::{AppError, AppResult};

/// Worker の設定。`wrangler.jsonc` の `vars` と Secrets から読む。
pub struct Config {
    /// 例: `https://<team>.cloudflareaccess.com`
    pub team_domain: String,
    /// Access アプリケーションの AUD タグ
    pub policy_aud: String,
    /// 位置履歴の保持期間（ミリ秒）。無期限保存を避けるための上限 (CLAUDE.md §5)。
    pub retention_ms: i64,
    /// 招待コードの有効期間（ミリ秒）
    pub invite_ttl_ms: i64,
}

impl Config {
    pub fn from_env(env: &Env) -> AppResult<Self> {
        Ok(Self {
            team_domain: required(env, "CHIKAKU_TEAM_DOMAIN")?
                .trim_end_matches('/')
                .to_owned(),
            policy_aud: required(env, "CHIKAKU_POLICY_AUD")?,
            retention_ms: number(env, "CHIKAKU_RETENTION_DAYS", 90) * 86_400_000,
            invite_ttl_ms: number(env, "CHIKAKU_INVITE_TTL_MINUTES", 1_440) * 60_000,
        })
    }
}

/// 認証に関わる設定が欠けたまま起動すると、検証が素通りしかねない。
/// 欠けている場合は 500 にして、開いたまま動き続けないようにする。
fn required(env: &Env, key: &str) -> AppResult<String> {
    env.var(key)
        .map(|v| v.to_string())
        .map_err(|_| AppError::Internal(format!("{key} が設定されていません")))
        .and_then(|v| {
            if v.trim().is_empty() {
                Err(AppError::Internal(format!("{key} が空です")))
            } else {
                Ok(v)
            }
        })
}

fn number(env: &Env, key: &str, default: i64) -> i64 {
    env.var(key)
        .ok()
        .and_then(|v| v.to_string().parse::<i64>().ok())
        .filter(|n| *n > 0)
        .unwrap_or(default)
}
