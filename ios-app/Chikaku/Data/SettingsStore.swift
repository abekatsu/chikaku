import Foundation
import Observation

/// 端末に保持する設定。位置履歴そのものは持たない（送信済みのものは即座に消える）。
/// Android の `data/SettingsStore.kt` と対。
struct Settings: Equatable {
    var consented: Bool
    var deviceId: String?
    var deviceToken: String?
    var familyId: String?
    var deviceName: String?
    var serverBaseURL: String
    var trackingEnabled: Bool
    var lastQueuedLat: Double?
    var lastQueuedLng: Double?
    var lastQueuedAt: Date?
    var lastSentAt: Date?

    var isPaired: Bool {
        !(deviceId ?? "").isEmpty && !(deviceToken ?? "").isEmpty
    }
}

@MainActor
@Observable
final class SettingsStore {

    private(set) var settings: Settings

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.settings = Settings(
            consented: defaults.bool(forKey: Key.consented),
            deviceId: defaults.string(forKey: Key.deviceId),
            // トークンだけは Keychain。他は平文で困らない。
            deviceToken: Keychain.get(Key.deviceToken),
            familyId: defaults.string(forKey: Key.familyId),
            deviceName: defaults.string(forKey: Key.deviceName),
            serverBaseURL: defaults.string(forKey: Key.serverBaseURL) ?? Config.defaultServerBaseURL,
            trackingEnabled: defaults.bool(forKey: Key.trackingEnabled),
            lastQueuedLat: defaults.object(forKey: Key.lastQueuedLat) as? Double,
            lastQueuedLng: defaults.object(forKey: Key.lastQueuedLng) as? Double,
            lastQueuedAt: defaults.object(forKey: Key.lastQueuedAt) as? Date,
            lastSentAt: defaults.object(forKey: Key.lastSentAt) as? Date
        )
    }

    func setConsented(_ consented: Bool) {
        defaults.set(consented, forKey: Key.consented)
        settings.consented = consented
    }

    func savePairing(deviceId: String, deviceToken: String, familyId: String, deviceName: String) {
        defaults.set(deviceId, forKey: Key.deviceId)
        defaults.set(familyId, forKey: Key.familyId)
        defaults.set(deviceName, forKey: Key.deviceName)
        Keychain.set(deviceToken, for: Key.deviceToken)
        settings.deviceId = deviceId
        settings.familyId = familyId
        settings.deviceName = deviceName
        settings.deviceToken = deviceToken
    }

    /// 「家族との接続を解除」で端末内のペアリング情報を全消去する（CLAUDE.md §5）。
    func clearPairing() {
        for key in [
            Key.deviceId, Key.familyId, Key.deviceName,
            Key.lastQueuedLat, Key.lastQueuedLng, Key.lastQueuedAt, Key.lastSentAt,
        ] {
            defaults.removeObject(forKey: key)
        }
        Keychain.remove(Key.deviceToken)
        defaults.set(false, forKey: Key.trackingEnabled)

        settings.deviceId = nil
        settings.deviceToken = nil
        settings.familyId = nil
        settings.deviceName = nil
        settings.lastQueuedLat = nil
        settings.lastQueuedLng = nil
        settings.lastQueuedAt = nil
        settings.lastSentAt = nil
        settings.trackingEnabled = false
    }

    func setServerBaseURL(_ url: String) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            defaults.removeObject(forKey: Key.serverBaseURL)
            settings.serverBaseURL = Config.defaultServerBaseURL
        } else {
            defaults.set(trimmed, forKey: Key.serverBaseURL)
            settings.serverBaseURL = trimmed
        }
    }

    func setTrackingEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Key.trackingEnabled)
        settings.trackingEnabled = enabled
    }

    func setLastQueued(lat: Double, lng: Double, at: Date) {
        defaults.set(lat, forKey: Key.lastQueuedLat)
        defaults.set(lng, forKey: Key.lastQueuedLng)
        defaults.set(at, forKey: Key.lastQueuedAt)
        settings.lastQueuedLat = lat
        settings.lastQueuedLng = lng
        settings.lastQueuedAt = at
    }

    func setLastSentAt(_ at: Date) {
        defaults.set(at, forKey: Key.lastSentAt)
        settings.lastSentAt = at
    }

    private enum Key {
        static let consented = "consented"
        static let deviceId = "device_id"
        static let deviceToken = "device_token"
        static let familyId = "family_id"
        static let deviceName = "device_name"
        static let serverBaseURL = "server_base_url"
        static let trackingEnabled = "tracking_enabled"
        static let lastQueuedLat = "last_queued_lat"
        static let lastQueuedLng = "last_queued_lng"
        static let lastQueuedAt = "last_queued_at"
        static let lastSentAt = "last_sent_at"
    }
}
