use anyhow::{Context, Result, bail};

use crate::auth::password;
use crate::clock;
use crate::db::Db;
use crate::validate::{MAX_EMAIL_LEN, MAX_NAME_LEN};

pub struct NewFamily {
    pub family_id: String,
    pub child_id: String,
}

/// 最初の家族と子アカウントを作る。
///
/// サインアップ用の公開エンドポイントは意図的に持たせていない。
/// 家族数人で使う前提なので、誰でも登録できる口を開けるより
/// 運用者が CLI で作るほうが安全で、実装も小さい。
pub async fn create_family(
    db: &Db,
    family_name: &str,
    email: &str,
    display_name: &str,
    password_plain: &str,
) -> Result<NewFamily> {
    let family_name = family_name.trim();
    let email = email.trim().to_lowercase();
    let display_name = display_name.trim();

    if family_name.is_empty() || family_name.chars().count() > MAX_NAME_LEN {
        bail!("家族の名前は 1〜{MAX_NAME_LEN} 文字で指定してください");
    }
    if display_name.is_empty() || display_name.chars().count() > MAX_NAME_LEN {
        bail!("表示名は 1〜{MAX_NAME_LEN} 文字で指定してください");
    }
    // 完全な RFC 準拠の検証はせず、ログイン ID として使えるかだけを見る。
    if email.len() > MAX_EMAIL_LEN || !email.contains('@') || email.contains(char::is_whitespace) {
        bail!("メールアドレスの形式が正しくありません");
    }
    if password_plain.chars().count() < 12 {
        bail!("パスワードは 12 文字以上にしてください");
    }

    let existing: Option<(String,)> =
        sqlx::query_as("SELECT id FROM children_accounts WHERE lower(email) = ?1")
            .bind(&email)
            .fetch_optional(db)
            .await?;
    if existing.is_some() {
        bail!("このメールアドレスは既に登録されています: {email}");
    }

    let family_id = uuid::Uuid::new_v4().to_string();
    let child_id = uuid::Uuid::new_v4().to_string();
    let now = clock::now();
    let password_hash = password::hash(password_plain).context("パスワードの保存に失敗しました")?;

    let mut tx = db.begin().await?;
    sqlx::query("INSERT INTO families (id, name, created_at) VALUES (?1, ?2, ?3)")
        .bind(&family_id)
        .bind(family_name)
        .bind(now)
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "INSERT INTO children_accounts \
         (id, family_id, email, display_name, password_hash, created_at) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
    )
    .bind(&child_id)
    .bind(&family_id)
    .bind(&email)
    .bind(display_name)
    .bind(&password_hash)
    .bind(now)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;

    Ok(NewFamily {
        family_id,
        child_id,
    })
}

/// 既存の家族に、きょうだい用の子アカウントを追加する (CLAUDE.md §4)。
pub async fn add_child(
    db: &Db,
    family_id: &str,
    email: &str,
    display_name: &str,
    password_plain: &str,
) -> Result<String> {
    let email = email.trim().to_lowercase();
    let display_name = display_name.trim();

    let family: Option<(String,)> = sqlx::query_as("SELECT id FROM families WHERE id = ?1")
        .bind(family_id)
        .fetch_optional(db)
        .await?;
    if family.is_none() {
        bail!("その家族は見つかりません: {family_id}");
    }
    if display_name.is_empty() || display_name.chars().count() > MAX_NAME_LEN {
        bail!("表示名は 1〜{MAX_NAME_LEN} 文字で指定してください");
    }
    if email.len() > MAX_EMAIL_LEN || !email.contains('@') || email.contains(char::is_whitespace) {
        bail!("メールアドレスの形式が正しくありません");
    }
    if password_plain.chars().count() < 12 {
        bail!("パスワードは 12 文字以上にしてください");
    }

    let existing: Option<(String,)> =
        sqlx::query_as("SELECT id FROM children_accounts WHERE lower(email) = ?1")
            .bind(&email)
            .fetch_optional(db)
            .await?;
    if existing.is_some() {
        bail!("このメールアドレスは既に登録されています: {email}");
    }

    let child_id = uuid::Uuid::new_v4().to_string();
    sqlx::query(
        "INSERT INTO children_accounts \
         (id, family_id, email, display_name, password_hash, created_at) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
    )
    .bind(&child_id)
    .bind(family_id)
    .bind(&email)
    .bind(display_name)
    .bind(password::hash(password_plain)?)
    .bind(clock::now())
    .execute(db)
    .await?;

    Ok(child_id)
}
