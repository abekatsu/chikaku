import Foundation
import SwiftData

/// 送信待ちの位置情報。圏外・サーバー停止中でも失わないよう、測位したものは
/// まずここに書き、送信成功時に削除する（CLAUDE.md §2.4）。
/// Android の Room `pending_locations` と対。
@Model
final class PendingLocation {
    var lat: Double
    var lng: Double
    /// 測位誤差（メートル）。取得できないときは -1。
    var accuracy: Double
    /// 測位時刻（UTC）
    var recordedAt: Date
    var batteryLevel: Int
    /// 送信を試みて失敗した回数。増えすぎたものは捨てる。
    var attempts: Int

    init(
        lat: Double,
        lng: Double,
        accuracy: Double,
        recordedAt: Date,
        batteryLevel: Int,
        attempts: Int = 0
    ) {
        self.lat = lat
        self.lng = lng
        self.accuracy = accuracy
        self.recordedAt = recordedAt
        self.batteryLevel = batteryLevel
        self.attempts = attempts
    }
}
