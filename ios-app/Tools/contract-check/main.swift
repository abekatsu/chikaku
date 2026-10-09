// iOS 側の実コード（ApiClient / ApiModels / Timestamp）を、ローカルで動かした
// Worker に対して実際に叩く。UI を経由しないので自動で回せる。
//
// 確認するのは docs/implementation-status.md §6.1 の「アプリが前提にする
// ステータスコードの意味」が iOS 側でも成立していること。サーバーを変えたら
// ここが落ちる、という関係にしておく。
//
//   swiftc -swift-version 6 -o /tmp/contract-check \
//     Chikaku/Config.swift Chikaku/Data/ApiModels.swift \
//     Chikaku/Data/ApiClient.swift Chikaku/Data/Timestamp.swift \
//     Tools/contract-check.swift
//   /tmp/contract-check <baseURL> <inviteCode>

import Foundation

var failures: [String] = []
var passed = 0

@MainActor
func check(_ name: String, _ condition: Bool, _ detail: String = "") {
    if condition {
        passed += 1
        print("  ✓ \(name)")
    } else {
        failures.append(name + (detail.isEmpty ? "" : " — \(detail)"))
        print("  ✗ \(name) \(detail)")
    }
}

let args = CommandLine.arguments
guard args.count >= 3 else {
    FileHandle.standardError.write(Data("使い方: contract-check <baseURL> <inviteCode>\n".utf8))
    exit(2)
}
let baseURL = args[1]
let inviteCode = args[2]

let api = ApiClient()

// ---------------------------------------------------------------- 時刻の形

print("時刻の整形")
let sample = Date(timeIntervalSince1970: 1_787_303_953.450)
let formatted = Timestamp.rfc3339(sample)
check(
    "RFC 3339 をミリ秒付き UTC で出す",
    formatted == "2026-08-21T09:19:13.450Z",
    "実際: \(formatted)"
)

// ---------------------------------------------------------------- URL の解決

print("URL の解決")
check(
    "末尾スラッシュの有無で結果が変わらない",
    resolve("https://example.com", path: "api/v1/location")
        == resolve("https://example.com/", path: "api/v1/location")
)
check(
    "パスを正しく繋ぐ",
    resolve("https://example.com/", path: "api/v1/location")?.absoluteString
        == "https://example.com/api/v1/location"
)

// ---------------------------------------------------------------- 端末登録

print("端末登録")
var deviceId = ""
var deviceToken = ""

switch await api.registerDevice(
    baseURL: baseURL,
    inviteCode: inviteCode,
    deviceName: "お父さんのiPhone",
    deviceModel: "Apple iPhone17,3"
) {
case .success(let response):
    deviceId = response.deviceId
    deviceToken = response.deviceToken
    check("招待コードで登録できる", !deviceId.isEmpty && !deviceToken.isEmpty)
case let other:
    check("招待コードで登録できる", false, "\(other)")
}

switch await api.registerDevice(
    baseURL: baseURL,
    inviteCode: inviteCode,
    deviceName: "二度目",
    deviceModel: "Apple iPhone17,3"
) {
case .unauthorized:
    check("使用済みの招待コードは unauthorized（=ペアリングやり直し）", true)
case let other:
    check("使用済みの招待コードは unauthorized（=ペアリングやり直し）", false, "\(other)")
}

guard !deviceToken.isEmpty else {
    print("\n登録できなかったため以降を打ち切ります")
    exit(1)
}

// ---------------------------------------------------------------- 位置送信

print("位置送信")
let now = Date()

@MainActor
    // 東京駅。check-private: synthetic
func payload(
    lat: Double = 35.681236,
    lng: Double = 139.767125,
    accuracy: Double = 12.5,
    at: Date = now,
    battery: Int = 87,
    id: String? = nil
) -> LocationPayload {
    LocationPayload(
        deviceId: id ?? deviceId,
        lat: lat,
        lng: lng,
        accuracy: accuracy,
        timestamp: Timestamp.rfc3339(at),
        batteryLevel: battery
    )
}

switch await api.postLocation(baseURL: baseURL, token: deviceToken, payload: payload()) {
case .success:
    check("正常な位置情報は success（=キューから削除）", true)
case let other:
    check("正常な位置情報は success（=キューから削除）", false, "\(other)")
}

// 応答だけが失われた場合の再送。サーバーが畳むのでアプリ側は成功として扱える。
switch await api.postLocation(baseURL: baseURL, token: deviceToken, payload: payload()) {
case .success:
    check("同一測位の再送も success（重複はサーバーが畳む）", true)
case let other:
    check("同一測位の再送も success（重複はサーバーが畳む）", false, "\(other)")
}

switch await api.postLocation(
    baseURL: baseURL,
    token: deviceToken,
    payload: payload(lat: 999.0, at: now.addingTimeInterval(1))
) {
case .clientError(let code, _):
    check("不正な緯度は clientError 400（=再試行せず破棄）", code == 400, "code=\(code)")
case let other:
    check("不正な緯度は clientError 400（=再試行せず破棄）", false, "\(other)")
}

switch await api.postLocation(
    baseURL: baseURL,
    token: deviceToken,
    payload: payload(at: now.addingTimeInterval(2), battery: 150)
) {
case .clientError(let code, _):
    check("範囲外の電池残量は clientError 400", code == 400, "code=\(code)")
case let other:
    check("範囲外の電池残量は clientError 400", false, "\(other)")
}

// 電池が取れないときの規約値。これが弾かれるとヘルスチェックごと落ちる。
switch await api.postLocation(
    baseURL: baseURL,
    token: deviceToken,
    payload: payload(at: now.addingTimeInterval(3), battery: -1)
) {
case .success:
    check("電池残量 -1（取得不能）は受け付けられる", true)
case let other:
    check("電池残量 -1（取得不能）は受け付けられる", false, "\(other)")
}

switch await api.postLocation(
    baseURL: baseURL,
    token: "wrong-token",
    payload: payload(at: now.addingTimeInterval(4))
) {
case .unauthorized:
    check("誤ったトークンは unauthorized（=ペアリングやり直し）", true)
case let other:
    check("誤ったトークンは unauthorized（=ペアリングやり直し）", false, "\(other)")
}

switch await api.postLocation(
    baseURL: baseURL,
    token: deviceToken,
    payload: payload(at: now.addingTimeInterval(5), id: "someone-elses-device")
) {
case .unauthorized:
    check("他端末になりすました device_id は unauthorized", true)
case let other:
    check("他端末になりすました device_id は unauthorized", false, "\(other)")
}

// ---------------------------------------------------------------- 結果

print("")
if failures.isEmpty {
    print("\(passed) 件すべて成立")
    exit(0)
} else {
    print("失敗 \(failures.count) 件 / 成功 \(passed) 件")
    for failure in failures { print("  - \(failure)") }
    exit(1)
}
