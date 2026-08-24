//! 招待コード発行と端末登録の総当たり対策。
//!
//! **WAF の Rate Limiting Rules は使えない。** あれはゾーン配下の機能で、
//! 本システムは独自ドメインを持たず workers.dev のホスト名で動く。
//! 代わりに Worker 内蔵の `ratelimit` バインディングで刻む。
//!
//! 刻みは Worker が動いた Cloudflare のロケーション単位で、
//! 全世界の合計ではない。単一の攻撃元を鈍らせる目的には足りるが、
//! 分散した総当たりには効かない。招待コードの防御は
//! 有効期限の短さ（既定24時間）と 27^8 の空間との組み合わせで成り立つ。
//!
//! **バインディングが無ければ 500 にして落とす。** 素通りさせると
//! 「レート制限を入れた」という前提だけが残って無防備になる
//! （`config::required` と同じ考え方）。

use worker::{Env, Request};

use crate::error::{AppError, AppResult};

/// 端末登録。認証前の経路なので送信元 IP で刻む。
pub const REGISTER: &str = "RL_REGISTER";

/// 招待コード発行。認証済みなので子アカウント単位で刻む。
/// IP ではなく本人で刻むのは、同じ家から複数人が見ても互いに巻き込まれないため。
pub const INVITE: &str = "RL_INVITE";

/// 送信元 IP。Cloudflare が必ず付けるヘッダだが、
/// 取れない場合は 1 つの鍵にまとめる。「制限しない」より
/// 「まとめて絞る」側に倒す。
pub fn client_ip(req: &Request) -> String {
    req.headers()
        .get("CF-Connecting-IP")
        .ok()
        .flatten()
        .unwrap_or_else(|| "unknown".to_owned())
}

/// 上限を超えていれば 429 を返す。
pub async fn check(env: &Env, binding: &str, key: &str) -> AppResult<()> {
    let limiter = env
        .rate_limiter(binding)
        .map_err(|e| AppError::Internal(format!("{binding} が設定されていません: {e}")))?;

    let outcome = limiter
        .limit(key.to_owned())
        .await
        .map_err(|e| AppError::Internal(format!("{binding} の判定に失敗しました: {e}")))?;

    if outcome.success {
        Ok(())
    } else {
        Err(AppError::TooManyRequests)
    }
}
