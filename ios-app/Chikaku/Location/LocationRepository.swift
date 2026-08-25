import CoreLocation
import Foundation
import UIKit

/// 「送るべきか」を判断してキューに積むところまでを受け持つ。
/// 実際の送信は `Uploader`。Android の `LocationRepository.kt` と対。
@MainActor
final class LocationRepository {

    private let queue: LocationQueue
    private let settings: SettingsStore

    init(queue: LocationQueue, settings: SettingsStore) {
        self.queue = queue
        self.settings = settings
    }

    /// 測位結果を受け取り、閾値を超えていればキューに積む。
    /// - Parameter force: ユーザーが「今すぐ送信」を押した場合など、閾値判定を飛ばしたいとき
    /// - Returns: キューに積んだら true
    @discardableResult
    func onLocationUpdate(_ location: CLLocation, now: Date = Date(), force: Bool = false) -> Bool {
        // 負の精度は「測位できていない」を意味する。
        guard location.horizontalAccuracy >= 0 else { return false }
        guard location.horizontalAccuracy <= LocationTuning.maxAcceptableAccuracyMeters else {
            return false
        }
        // 起動直後に返ってくるキャッシュ済みの古い位置を弾く。
        let age = now.timeIntervalSince(location.timestamp)
        if !force, age > LocationTuning.maxLocationAgeSeconds { return false }

        guard force || shouldSend(location, now: now) else { return false }

        queue.insert(
            PendingLocation(
                lat: location.coordinate.latitude,
                lng: location.coordinate.longitude,
                accuracy: location.horizontalAccuracy,
                // 端末時計がずれていても順序が壊れないよう、測位時刻そのものを使う。
                recordedAt: location.timestamp,
                batteryLevel: Self.batteryLevel()
            )
        )
        queue.trim(to: LocationTuning.maxQueueSize)
        settings.setLastQueued(
            lat: location.coordinate.latitude,
            lng: location.coordinate.longitude,
            at: now
        )
        return true
    }

    private func shouldSend(_ location: CLLocation, now: Date) -> Bool {
        let current = settings.settings
        // 初回は無条件で送る。
        guard let lastLat = current.lastQueuedLat, let lastLng = current.lastQueuedLng else {
            return true
        }
        // ヘルスチェック間隔を超えたら、動いていなくても送る。
        if let lastAt = current.lastQueuedAt,
           now.timeIntervalSince(lastAt) >= LocationTuning.heartbeatInterval {
            return true
        }
        let previous = CLLocation(latitude: lastLat, longitude: lastLng)
        return previous.distance(from: location) >= LocationTuning.sendDistanceThresholdMeters
    }

    /// 取得できないときは -1。サーバーはこの規約で -1 を受け付ける。
    static func batteryLevel() -> Int {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        guard level >= 0 else { return -1 }
        return Int((level * 100).rounded())
    }
}
