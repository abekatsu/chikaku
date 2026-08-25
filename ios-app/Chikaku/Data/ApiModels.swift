import Foundation

/// サーバーの `server/src/routes/devices.rs` / `location.rs` と対。
/// Android の `data/ApiModels.kt` と同じ形を保つこと。

struct RegisterDeviceRequest: Encodable {
    let inviteCode: String
    let deviceName: String
    /// 端末モデル名。ダッシュボード側で見分けるための補助情報。
    let deviceModel: String

    enum CodingKeys: String, CodingKey {
        case inviteCode = "invite_code"
        case deviceName = "device_name"
        case deviceModel = "device_model"
    }
}

struct RegisterDeviceResponse: Decodable {
    let deviceId: String
    let deviceToken: String
    let familyId: String

    enum CodingKeys: String, CodingKey {
        case deviceId = "device_id"
        case deviceToken = "device_token"
        case familyId = "family_id"
    }
}

/// CLAUDE.md §2.4 の送信ペイロード。
struct LocationPayload: Encodable {
    let deviceId: String
    let lat: Double
    let lng: Double
    let accuracy: Double
    /// RFC 3339 (UTC)。サーバー側の時刻表現に依存しないよう文字列で送る。
    let timestamp: String
    /// 取得できないときは -1。サーバーは -1...100 を受け付ける。
    let batteryLevel: Int

    enum CodingKeys: String, CodingKey {
        case deviceId = "device_id"
        case lat
        case lng
        case accuracy
        case timestamp
        case batteryLevel = "battery_level"
    }
}

struct ErrorResponse: Decodable {
    let message: String?
    let error: String?
}
