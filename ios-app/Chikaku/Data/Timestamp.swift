import Foundation

/// サーバーとやり取りする時刻表現。
///
/// サーバーの `clock::parse_rfc3339` が読む形に揃える。ミリ秒まで出すのは、
/// 重複排除が `(device_id, recorded_at)` のエポックミリ秒で効くため
/// （秒に丸めると、圏外復帰後の再送で別の行として入りうる）。
/// Android の `DateTimeFormatter.ISO_INSTANT` と同じ出力になる。
enum Timestamp {

    static func rfc3339(_ date: Date) -> String {
        date.formatted(style)
    }

    /// `ISO8601DateFormatter` ではなく `FormatStyle` を使うのは、前者が
    /// 参照型で Sendable でないため。送信はメインアクタ以外からも呼ばれうる。
    private static let style = Date.ISO8601FormatStyle(
        includingFractionalSeconds: true,
        timeZone: .gmt
    )
}
