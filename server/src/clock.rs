use chrono::{DateTime, TimeZone, Utc};

/// DB に入れる時刻表現。UTC の Unix エポックミリ秒。
pub type Millis = i64;

/// 現在時刻。`chrono::Utc::now()` は wasm で追加のフィーチャを要求するため、
/// Workers ランタイムの `Date` を使う (ADR-5)。
pub fn now() -> Millis {
    worker::Date::now().as_millis() as i64
}

fn to_datetime(ms: Millis) -> DateTime<Utc> {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_iso_instant() {
        let ms = 1_787_303_953_450;
        assert_eq!(to_rfc3339(ms), "2026-08-21T09:19:13.450Z");
        assert_eq!(parse_rfc3339("2026-08-21T09:19:13.450Z"), Some(ms));
    }

    #[test]
    fn accepts_offsets_and_normalizes_to_utc() {
        assert_eq!(
            parse_rfc3339("2026-08-21T18:19:13.450+09:00"),
            parse_rfc3339("2026-08-21T09:19:13.450Z")
        );
    }

    #[test]
    fn rejects_garbage() {
        assert_eq!(parse_rfc3339("きのう"), None);
        assert_eq!(parse_rfc3339(""), None);
    }
}
