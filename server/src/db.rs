use anyhow::{Context, Result};
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use sqlx::{Pool, Sqlite};
use std::str::FromStr;
use std::time::Duration;

pub type Db = Pool<Sqlite>;

pub async fn connect(database_url: &str) -> Result<Db> {
    let options = SqliteConnectOptions::from_str(database_url)
        .with_context(|| format!("接続文字列を解釈できません: {database_url}"))?
        .create_if_missing(true)
        // 参照整合性は既定で無効なので明示的に入れる。
        .foreign_keys(true)
        // 書き込み中も読み取りを止めないようにする。
        .journal_mode(sqlx::sqlite::SqliteJournalMode::Wal)
        .synchronous(sqlx::sqlite::SqliteSynchronous::Normal)
        .busy_timeout(Duration::from_secs(5));

    let pool = SqlitePoolOptions::new()
        .max_connections(8)
        .acquire_timeout(Duration::from_secs(10))
        .connect_with(options)
        .await
        .context("データベースに接続できませんでした")?;

    sqlx::migrate!("./migrations")
        .run(&pool)
        .await
        .context("マイグレーションに失敗しました")?;

    Ok(pool)
}
