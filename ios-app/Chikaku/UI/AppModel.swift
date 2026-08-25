import CoreLocation
import Foundation
import Observation
import UIKit

enum PairingState: Equatable {
    case idle
    case inProgress
    case failed(String)
}

/// 画面から使う操作の集約。Android の `MainViewModel` と対。
@MainActor
@Observable
final class AppModel {

    private let graph: Graph

    var pairing: PairingState = .idle

    init(graph: Graph) {
        self.graph = graph
    }

    var settings: Settings { graph.settings.settings }
    var pendingCount: Int { graph.uploader.pendingCount }
    var tracker: LocationTracker { graph.tracker }

    func onConsent() {
        graph.settings.setConsented(true)
    }

    func pair(inviteCode: String, deviceName: String, serverBaseURL: String) {
        let code = inviteCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else {
            pairing = .failed(S.pairingErrorEmptyCode)
            return
        }
        pairing = .inProgress

        Task { @MainActor in
            graph.settings.setServerBaseURL(serverBaseURL)
            let baseURL = graph.settings.settings.serverBaseURL
            let trimmedName = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = trimmedName.isEmpty ? UIDevice.current.name : trimmedName

            let result = await ApiClient().registerDevice(
                baseURL: baseURL,
                inviteCode: code,
                deviceName: name,
                deviceModel: Self.deviceModel()
            )

            switch result {
            case .success(let response):
                graph.settings.savePairing(
                    deviceId: response.deviceId,
                    deviceToken: response.deviceToken,
                    familyId: response.familyId,
                    deviceName: name
                )
                pairing = .idle

            case .networkError:
                pairing = .failed(S.pairingErrorFailed(S.pairingErrorNetwork))

            case .serverError(let code, _):
                pairing = .failed(S.pairingErrorFailed(S.pairingErrorServer(code)))

            case .unauthorized:
                pairing = .failed(S.pairingErrorFailed(S.pairingErrorInvalidCode))

            case .clientError(_, let message):
                pairing = .failed(S.pairingErrorFailed(message ?? S.pairingErrorInvalidCode))
            }
        }
    }

    func dismissPairingError() {
        pairing = .idle
    }

    func startTracking() {
        graph.settings.setTrackingEnabled(true)
        graph.tracker.start()
        BackgroundTaskScheduler.scheduleHeartbeat()
        BackgroundTaskScheduler.scheduleUpload()
    }

    func stopTracking() {
        graph.settings.setTrackingEnabled(false)
        graph.tracker.stop()
        BackgroundTaskScheduler.cancelAll()
    }

    func sendNow() {
        Task { @MainActor in
            // 測位を待ってから掃き出す。待たないと、いま取った点は
            // 次の機会まで送られず「今すぐ送信」を押した意味が薄れる。
            await graph.tracker.oneShot()
            await graph.uploader.flush()
        }
    }

    /// 家族との接続を解除し、端末に残る情報を消す（CLAUDE.md §5）。
    func unpair() {
        graph.tracker.stop()
        BackgroundTaskScheduler.cancelAll()
        graph.queue.clear()
        graph.uploader.refreshPendingCount()
        graph.settings.clearPairing()
    }

    func refresh() {
        graph.uploader.refreshPendingCount()
    }

    /// `iPhone17,3` のような機種識別子。ダッシュボードで端末を見分ける補助情報。
    private static func deviceModel() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let identifier = withUnsafeBytes(of: &systemInfo.machine) { buffer -> String in
            let bytes = buffer.prefix(while: { $0 != 0 })
            return String(decoding: bytes, as: UTF8.self)
        }
        return identifier.isEmpty ? UIDevice.current.model : "Apple \(identifier)"
    }
}
