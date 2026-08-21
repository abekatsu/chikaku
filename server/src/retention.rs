//! 期限切れデータの削除 (CLAUDE.md §5)。
//!
//! Axum 版は `tokio::spawn` の常駐ループだったが、Workers に常駐プロセスは
//! 無いため Cron Trigger の `scheduled` ハンドラから呼ぶ (ADR-4)。

use worker::D1Database;

use crate::clock;
use crate::db;
use crate::error::AppResult;

pub struct Swept {
    pub events: u64,
    pub invites: u64,
}

/// 保持期間を過ぎた位置履歴と、使われないまま期限切れになった招待コードを消す。
///
/// 使用済みの招待コードは残す（どの端末がどのコードで登録されたか辿れる）。
pub async fn sweep(database: &D1Database, retention_ms: i64) -> AppResult<Swept> {
    let now = clock::now();
    let cutoff = now - retention_ms;

    let events = db::run(
        database,
        "DELETE FROM location_events WHERE recorded_at < ?1",
        &[db::num(cutoff)],
    )
    .await?;

    let invites = db::run(
        database,
        "DELETE FROM invite_codes WHERE used_at IS NULL AND expires_at < ?1",
        &[db::num(now)],
    )
    .await?;

    Ok(Swept { events, invites })
}
