//! D1 への薄いラッパ。
//!
//! **D1 は JavaScript の BigInt を受け付けない** (ADR-4)。Rust の `i64` を
//! そのまま bind すると `D1_TYPE_ERROR` になるため、必ず `num()` を通して
//! `f64` として渡す。エポックミリ秒は 2^53 に遠く及ばないので精度は失われない。

use serde::de::DeserializeOwned;
use wasm_bindgen::JsValue;
use worker::D1Database;

use crate::error::AppResult;

/// 整数を D1 に渡せる形にする。**i64 を直接 into() しないこと。**
pub fn num(v: i64) -> JsValue {
    JsValue::from_f64(v as f64)
}

/// 真偽値。SQLite に BOOLEAN は無いので INTEGER 0/1 として入れる。
pub fn flag(v: bool) -> JsValue {
    num(i64::from(v))
}

pub fn real(v: f64) -> JsValue {
    JsValue::from_f64(v)
}

pub fn text(v: &str) -> JsValue {
    JsValue::from_str(v)
}

/// 省略可能なパラメータ。`?N IS NULL OR col = ?N` の形で使う。
pub fn opt_text(v: Option<&str>) -> JsValue {
    match v {
        Some(s) => JsValue::from_str(s),
        None => JsValue::NULL,
    }
}

/// 省略可能な真偽値。**None は 0 ではなく NULL。**
/// 「報告が無い」を「false」に化けさせないため（Issue #4 / #13）。
pub fn opt_flag(v: Option<bool>) -> JsValue {
    match v {
        Some(b) => flag(b),
        None => JsValue::NULL,
    }
}

/// 1 行だけ取る。行が無ければ `None`。
pub async fn first<T: DeserializeOwned>(
    db: &D1Database,
    sql: &str,
    params: &[JsValue],
) -> AppResult<Option<T>> {
    Ok(db.prepare(sql).bind(params)?.first::<T>(None).await?)
}

/// 全行取る。
pub async fn all<T: DeserializeOwned>(
    db: &D1Database,
    sql: &str,
    params: &[JsValue],
) -> AppResult<Vec<T>> {
    let result = db.prepare(sql).bind(params)?.all().await?;
    Ok(result.results::<T>()?)
}

/// 更新系を実行して、影響を受けた行数を返す。
pub async fn run(db: &D1Database, sql: &str, params: &[JsValue]) -> AppResult<u64> {
    let result = db.prepare(sql).bind(params)?.run().await?;
    Ok(changes(&result))
}

/// 複数文をひとまとまりで実行する。
/// D1 の `batch` は SQL トランザクションとして働き、
/// 途中で失敗すると全体がロールバックされる (ADR-4)。
pub async fn batch(
    db: &D1Database,
    statements: Vec<worker::D1PreparedStatement>,
) -> AppResult<Vec<u64>> {
    let results = db.batch(statements).await?;
    Ok(results.iter().map(changes).collect())
}

fn changes(result: &worker::D1Result) -> u64 {
    result
        .meta()
        .ok()
        .flatten()
        .and_then(|m| m.changes)
        .unwrap_or(0) as u64
}
