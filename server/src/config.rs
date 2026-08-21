use std::net::SocketAddr;
use std::time::Duration;

use anyhow::{Context, Result};

/// 起動時に環境変数から読む設定。
///
/// TLS はここでは終端しない。CLAUDE.md §5 の「通信は全て TLS 必須」は
/// 前段のリバースプロキシ (nginx / Caddy) で満たす前提で、
/// 既定の待ち受けを 127.0.0.1 にしてプロキシ経由以外で外に出ないようにしている。
#[derive(Debug, Clone)]
pub struct Config {
    pub database_url: String,
    pub bind: SocketAddr,
    /// 位置履歴の保持期間。無期限保存を避けるための上限 (CLAUDE.md §5)。
    pub retention: Duration,
    pub session_ttl: Duration,
    pub invite_ttl: Duration,
    /// ダッシュボードのオリジン。空なら CORS を一切許可しない。
    pub cors_origins: Vec<String>,
}

impl Config {
    pub fn from_env() -> Result<Self> {
        Ok(Self {
            database_url: env_or("CHIKAKU_DATABASE_URL", "sqlite://chikaku.db?mode=rwc"),
            bind: env_or("CHIKAKU_BIND", "127.0.0.1:8080")
                .parse()
                .context("CHIKAKU_BIND は host:port 形式で指定してください")?,
            retention: Duration::from_secs(parse_num("CHIKAKU_RETENTION_DAYS", 90)? * 86_400),
            session_ttl: Duration::from_secs(parse_num("CHIKAKU_SESSION_TTL_HOURS", 720)? * 3_600),
            invite_ttl: Duration::from_secs(parse_num("CHIKAKU_INVITE_TTL_MINUTES", 1_440)? * 60),
            cors_origins: env_or("CHIKAKU_CORS_ORIGINS", "")
                .split(',')
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_owned)
                .collect(),
        })
    }
}

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_owned())
}

fn parse_num(key: &str, default: u64) -> Result<u64> {
    match std::env::var(key) {
        Ok(v) => v
            .parse()
            .with_context(|| format!("{key} には正の整数を指定してください (指定値: {v})")),
        Err(_) => Ok(default),
    }
}
