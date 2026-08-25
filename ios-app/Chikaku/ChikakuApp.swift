import SwiftUI
import UIKit

@main
struct ChikakuApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup {
            RootView(graph: Graph.shared)
        }
    }
}

/// 位置情報による起動を受けるために `UIApplicationDelegate` を挟む。
/// SwiftUI のライフサイクルだけでは、この経路の起動を取りこぼす。
@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let graph = Graph.shared

        // register は起動処理を抜ける前に済ませる必要がある。
        BackgroundTaskScheduler.register(uploader: graph.uploader, tracker: graph.tracker)

        if launchOptions?[.location] != nil {
            // 終了状態から位置イベントで叩き起こされた。ここで測位を張り直さないと、
            // この1回の通知で終わってしまう。
            Log.lifecycle.info("位置情報により起動しました")
        }
        #if DEBUG
        // 開発用。ペアリング後の画面をシミュレータで確認するための細工で、
        // リリースビルドには含まれない。
        //   xcrun simctl launch <dev> com.damburisoft.chikaku.watch -chikakuSeedPairing
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-chikakuSeedConsent") {
            graph.settings.setConsented(true)
        }
        if arguments.contains("-chikakuSeedPairing") {
            graph.settings.setConsented(true)
            graph.settings.savePairing(
                deviceId: "preview-device",
                deviceToken: "preview-token",
                familyId: "preview-family",
                deviceName: "お父さんのiPhone"
            )
        }
        #endif

        graph.resumeTrackingIfNeeded()

        // 前面に戻ったタイミングでも溜まった分を掃き出す。
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { _ in
            Task { @MainActor in
                await Graph.shared.uploader.flush()
            }
        }
        return true
    }
}
