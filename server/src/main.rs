use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use tracing_subscriber::EnvFilter;

use chikaku_server::{bootstrap, config::Config, db, retention, routes, state::AppState};

#[derive(Parser)]
#[command(
    name = "chikaku-server",
    about = "高齢者見守り位置情報システムのサーバー"
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand)]
enum Command {
    /// HTTP サーバーを起動する（既定）
    Serve,
    /// 最初の家族と子アカウントを作る
    CreateFamily {
        #[arg(long)]
        family_name: String,
        #[arg(long)]
        email: String,
        #[arg(long)]
        display_name: String,
        /// 12 文字以上。シェル履歴に残さないよう CHIKAKU_ADMIN_PASSWORD でも渡せる
        #[arg(long, env = "CHIKAKU_ADMIN_PASSWORD", hide_env_values = true)]
        password: String,
    },
    /// 既存の家族に子アカウントを追加する
    AddChild {
        #[arg(long)]
        family_id: String,
        #[arg(long)]
        email: String,
        #[arg(long)]
        display_name: String,
        #[arg(long, env = "CHIKAKU_ADMIN_PASSWORD", hide_env_values = true)]
        password: String,
    },
    /// 保持期間を過ぎたデータを今すぐ削除する
    Sweep,
}

#[tokio::main]
async fn main() -> Result<()> {
    // .env は開発用。本番では環境変数を直接与える。
    let _ = dotenvy::dotenv();
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_env("CHIKAKU_LOG")
                .unwrap_or_else(|_| EnvFilter::new("info,tower_http=debug")),
        )
        .init();

    let cli = Cli::parse();
    let config = Config::from_env()?;
    let db = db::connect(&config.database_url).await?;

    match cli.command.unwrap_or(Command::Serve) {
        Command::Serve => serve(config, db).await,

        Command::CreateFamily {
            family_name,
            email,
            display_name,
            password,
        } => {
            let created =
                bootstrap::create_family(&db, &family_name, &email, &display_name, &password)
                    .await?;
            // ダッシュボードの URL 組み立てに要るので family_id を標準出力に出す。
            println!("family_id = {}", created.family_id);
            println!("child_id  = {}", created.child_id);
            Ok(())
        }

        Command::AddChild {
            family_id,
            email,
            display_name,
            password,
        } => {
            let child_id =
                bootstrap::add_child(&db, &family_id, &email, &display_name, &password).await?;
            println!("child_id = {child_id}");
            Ok(())
        }

        Command::Sweep => {
            let deleted = retention::sweep(&db, config.retention).await?;
            println!("削除した位置履歴: {deleted} 件");
            Ok(())
        }
    }
}

async fn serve(config: Config, db: db::Db) -> Result<()> {
    retention::spawn(db.clone(), config.retention);

    let bind = config.bind;
    let retention_days = config.retention.as_secs() / 86_400;
    let app = routes::router(AppState::new(db, config));

    let listener = tokio::net::TcpListener::bind(bind)
        .await
        .with_context(|| format!("{bind} を待ち受けられませんでした"))?;

    tracing::info!(%bind, retention_days, "サーバーを起動しました");
    // TLS は前段のリバースプロキシで終端する前提 (CLAUDE.md §5)。
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .context("サーバーが異常終了しました")?;

    Ok(())
}

/// SIGTERM / Ctrl-C で処理中のリクエストを捨てずに終わる。
async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };

    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut sig) => {
                sig.recv().await;
            }
            Err(e) => tracing::error!(%e, "SIGTERM を待ち受けられません"),
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {}
        _ = terminate => {}
    }
    tracing::info!("停止します");
}
