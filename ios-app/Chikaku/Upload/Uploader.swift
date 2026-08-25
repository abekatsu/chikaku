import Foundation
import Observation

/// キューに溜まった位置情報をサーバーへ流し込む。
/// Android の `UploadWorker` と対（WorkManager の指数バックオフに当たる部分は
/// `BackgroundTaskScheduler` が受け持つ）。
@MainActor
@Observable
final class Uploader {

    enum Outcome {
        /// キューを空にした（あるいは元から空だった）。
        case completed
        /// 圏外・サーバー障害。時間をおいてやり直す。
        case retryLater
        /// 端末トークンが無効。ユーザーがペアリングし直すまで送りようがない。
        case needsRepairing
        /// 別の掃き出しが走っている最中だった。何もしていない。
        case alreadyRunning
    }

    private let queue: LocationQueue
    private let settings: SettingsStore
    private let api: ApiClient

    /// 二重起動を防ぐ。位置更新・前面復帰・バックグラウンドタスクの3経路から
    /// 呼ばれるため、重なると同じ行を2回送って無駄な通信になる。
    private var isFlushing = false

    private(set) var pendingCount: Int = 0

    init(queue: LocationQueue, settings: SettingsStore, api: ApiClient) {
        self.queue = queue
        self.settings = settings
        self.api = api
        self.pendingCount = queue.count()
    }

    func refreshPendingCount() {
        pendingCount = queue.count()
    }

    @discardableResult
    func flush() async -> Outcome {
        // `retryLater` を返してはいけない。呼び出し側がそれを見て
        // バックグラウンドタスクを予約し直すが、`BGTaskScheduler` は同じ識別子の
        // 予約を**置き換える**ため、本当に必要だった再送の実行時刻が後ろへずれる。
        guard !isFlushing else { return .alreadyRunning }
        isFlushing = true
        defer {
            isFlushing = false
            refreshPendingCount()
        }

        let current = settings.settings
        guard current.isPaired, let deviceId = current.deviceId, let token = current.deviceToken else {
            // 未ペアリング。溜めておいても宛先がないので捨てる。
            queue.clear()
            return .completed
        }
        let baseURL = current.serverBaseURL

        while true {
            let batch = queue.oldest(limit: batchSize)
            if batch.isEmpty { return .completed }

            for row in batch {
                let result = await api.postLocation(
                    baseURL: baseURL,
                    token: token,
                    payload: payload(for: row, deviceId: deviceId)
                )
                switch result {
                case .success:
                    queue.delete(row)
                    settings.setLastSentAt(Date())

                case .networkError(let error):
                    Log.upload.info(
                        "送信できませんでした。後で再試行します: \(error.localizedDescription, privacy: .public)"
                    )
                    return .retryLater

                case .serverError(let code, _):
                    Log.upload.notice("サーバーエラー \(code, privacy: .public)。後で再試行します")
                    return .retryLater

                case .unauthorized:
                    Log.upload.error("認証に失敗しました。ペアリングのやり直しが必要です")
                    return .needsRepairing

                case .clientError(let code, let message):
                    // リクエストが受け付けられない。再試行しても同じなので回数を数えて捨てる。
                    Log.upload.notice(
                        "送信を拒否されました (\(code, privacy: .public)): \(message ?? "", privacy: .public)"
                    )
                    if row.attempts + 1 >= LocationTuning.maxSendAttempts {
                        queue.delete(row)
                    } else {
                        queue.incrementAttempts(row)
                        return .retryLater
                    }
                }
            }
        }
    }

    private func payload(for row: PendingLocation, deviceId: String) -> LocationPayload {
        LocationPayload(
            deviceId: deviceId,
            lat: row.lat,
            lng: row.lng,
            accuracy: row.accuracy,
            timestamp: Timestamp.rfc3339(row.recordedAt),
            batteryLevel: row.batteryLevel
        )
    }

    private let batchSize = 50
}
