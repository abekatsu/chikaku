use std::time::Duration;

use crate::clock;
use crate::db::Db;

/// 掃除を回す間隔。保持期間が日単位なので 6 時間ごとで十分。
const SWEEP_INTERVAL: Duration = Duration::from_secs(6 * 60 * 60);

/// 期限切れのデータを消す。
///
/// 位置履歴を無期限に持たないことは要件 (CLAUDE.md §5)。
/// 併せて、失効したセッションと使われなかった招待コードも落とす。
pub async fn sweep(db: &Db, retention: Duration) -> Result<u64, sqlx::Error> {
    let now = clock::now();
    let cutoff = now - retention.as_millis() as i64;

    let events = sqlx::query("DELETE FROM location_events WHERE recorded_at < ?1")
        .bind(cutoff)
        .execute(db)
        .await?
        .rows_affected();

    sqlx::query("DELETE FROM child_sessions WHERE expires_at < ?1")
        .bind(now)
        .execute(db)
        .await?;

    // 使われないまま期限切れになった招待コードは、使用済みのものと違い
    // 監査上たどる必要がないので消してよい。
    sqlx::query("DELETE FROM invite_codes WHERE used_at IS NULL AND expires_at < ?1")
        .bind(now)
        .execute(db)
        .await?;

    Ok(events)
}

/// 起動時に 1 回、その後は一定間隔で掃除を続ける常駐タスク。
pub fn spawn(db: Db, retention: Duration) {
    tokio::spawn(async move {
        let mut ticker = tokio::time::interval(SWEEP_INTERVAL);
        loop {
            ticker.tick().await;
            match sweep(&db, retention).await {
                Ok(0) => tracing::debug!("保持期間を過ぎた位置履歴はありませんでした"),
                Ok(n) => tracing::info!(deleted = n, "保持期間を過ぎた位置履歴を削除しました"),
                // 一時的な失敗でタスクを終わらせず、次の周期に賭ける。
                Err(e) => tracing::error!(%e, "古いデータの削除に失敗しました"),
            }
        }
    });
}
