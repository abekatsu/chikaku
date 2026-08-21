use chrono::{DateTime, TimeZone, Utc};

/// DB に入れる時刻表現。UTC の Unix エポックミリ秒。
pub type Millis = i64;

pub fn now() -> Millis {
    Utc::now().timestamp_millis()
}

pub fn to_datetime(ms: Millis) -> DateTime<Utc> {
    // timestamp_millis() 由来の値なので範囲外にはならないが、
    // DB から読んだ壊れた値でも panic させないよう UNIX epoch に丸める。
    Utc.timestamp_millis_opt(ms).single().unwrap_or_default()
}

/// JSON に出す時刻は RFC 3339 (UTC) に揃える。
pub fn to_rfc3339(ms: Millis) -> String {
    to_datetime(ms).to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

/// Android が `DateTimeFormatter.ISO_INSTANT` で送ってくる文字列を読む。
pub fn parse_rfc3339(s: &str) -> Option<Millis> {
    DateTime::parse_from_rfc3339(s)
        .ok()
        .map(|dt| dt.with_timezone(&Utc).timestamp_millis())
}
