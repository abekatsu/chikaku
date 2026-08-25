import Foundation
import SwiftData

/// 簡易サービスロケータ。Android の `Graph.kt` と対で、DI ライブラリは使わない。
///
/// 位置情報による**バックグラウンド起動**では SwiftUI の画面が作られないまま
/// デリゲートだけが呼ばれる。そのため依存の組み立ては View 側ではなく
/// `AppDelegate` の起動処理からここ一箇所で行う。
@MainActor
final class Graph {

    static let shared = Graph()

    let settings: SettingsStore
    let queue: LocationQueue
    let repository: LocationRepository
    let tracker: LocationTracker
    let uploader: Uploader

    private init() {
        let container: ModelContainer
        do {
            container = try LocationQueue.makeContainer()
        } catch {
            // ストアが開けない状態でも見守り自体は続けたい。メモリ上のキューに退避する。
            // この場合アプリが終了すると未送信分は失われるが、無言で停止するよりましと判断した。
            Log.queue.error("キューを開けませんでした: \(error.localizedDescription, privacy: .public)")
            // ここが失敗するのはスキーマ定義そのものが壊れているときだけで、
            // 開発中に必ず露見する。実行時の条件では起きないため try! でよい。
            container = try! LocationQueue.makeContainer(inMemory: true)
        }

        let settings = SettingsStore()
        let queue = LocationQueue(container: container)
        let repository = LocationRepository(queue: queue, settings: settings)

        self.settings = settings
        self.queue = queue
        self.repository = repository
        self.uploader = Uploader(queue: queue, settings: settings, api: ApiClient())
        self.tracker = LocationTracker(repository: repository, settings: settings)

        // 位置を積んだら送信を試す。iOS ではアプリが起きている時間が
        // 「位置更新が来た瞬間」に集中するため、ここが送信の主経路になる。
        let uploader = self.uploader
        self.tracker.onQueued = {
            Task { @MainActor in
                let outcome = await uploader.flush()
                if outcome == .retryLater {
                    BackgroundTaskScheduler.scheduleUpload()
                }
            }
        }
    }

    /// 同意済み・ペアリング済み・見守りON のときだけ測位を復帰させる。
    func resumeTrackingIfNeeded() {
        let current = settings.settings
        guard current.consented, current.isPaired, current.trackingEnabled else { return }
        tracker.start()
        BackgroundTaskScheduler.scheduleHeartbeat()
        BackgroundTaskScheduler.scheduleUpload()
    }
}
