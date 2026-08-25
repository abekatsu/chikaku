import BackgroundTasks
import Foundation

/// 位置更新が来ないときの保険。Android の `WorkManager`（`UploadWorker` の
/// 指数バックオフと `WatchdogWorker` の死活監視）に相当する。
///
/// **ただし iOS は実行を保証しない。** `BGTaskScheduler` の起動時刻は
/// 端末の使用状況・充電状態・電池残量から OS が決め、要求どおりには回らない。
/// そのため送信の主経路はあくまで「位置更新のたびに掃き出す」ほうで、
/// ここは圏外が長引いた場合と、完全に静止している場合の穴埋めに徹する。
@MainActor
enum BackgroundTaskScheduler {

    /// `didFinishLaunchingWithOptions` を抜ける前に呼ぶこと。
    /// 起動完了後に register するとクラッシュする。
    static func register(uploader: Uploader, tracker: LocationTracker) {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Config.BackgroundTask.upload,
            using: .main
        ) { task in
            MainActor.assumeIsolated {
                handleUpload(task, uploader: uploader)
            }
        }

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Config.BackgroundTask.heartbeat,
            using: .main
        ) { task in
            MainActor.assumeIsolated {
                handleHeartbeat(task, uploader: uploader, tracker: tracker)
            }
        }
    }

    // MARK: - 予約

    /// 圏外などで送り切れなかったときに次の機会を取る。
    static func scheduleUpload(after delay: TimeInterval = 15 * 60) {
        let request = BGProcessingTaskRequest(identifier: Config.BackgroundTask.upload)
        request.requiresNetworkConnectivity = true
        // 電池のあるうちに送りたい。充電を待たせると圏外復帰が遅れる。
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: delay)
        submit(request)
    }

    /// 動きがなくても定期的に生存を知らせる（CLAUDE.md §2.2 のヘルスチェック）。
    static func scheduleHeartbeat() {
        let request = BGAppRefreshTaskRequest(identifier: Config.BackgroundTask.heartbeat)
        request.earliestBeginDate = Date(timeIntervalSinceNow: LocationTuning.heartbeatInterval)
        submit(request)
    }

    static func cancelAll() {
        BGTaskScheduler.shared.cancelAllTaskRequests()
    }

    private static func submit(_ request: BGTaskRequest) {
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // シミュレータでは常に失敗する。実機でも予約数の上限で弾かれうるが、
            // 送信の主経路は位置更新側なので、ここで落とす理由はない。
            Log.upload.debug(
                "バックグラウンドタスクを予約できませんでした: \(error.localizedDescription, privacy: .public)"
            )
        }
    }

    // MARK: - 実行

    private static func handleUpload(_ task: BGTask, uploader: Uploader) {
        // 次の機会を先に押さえる。処理の途中で終了させられても連鎖が切れないように。
        scheduleUpload()

        let work = Task { @MainActor in
            let outcome = await uploader.flush()
            task.setTaskCompleted(success: outcome != .retryLater)
        }
        task.expirationHandler = {
            work.cancel()
        }
    }

    private static func handleHeartbeat(_ task: BGTask, uploader: Uploader, tracker: LocationTracker) {
        scheduleHeartbeat()

        let work = Task { @MainActor in
            // 静止していても現在地を1点取って「生きている」ことを伝える。
            // **測位の完了を待ってから掃き出す。** 待たずに送ると、いま取った点が
            // キューに入る前にタスクが終わり、アプリが停止させられて
            // ヘルスチェックそのものが落ちる。
            await tracker.oneShot()
            let outcome = await uploader.flush()
            task.setTaskCompleted(success: outcome != .retryLater)
        }
        task.expirationHandler = {
            work.cancel()
        }
    }
}
