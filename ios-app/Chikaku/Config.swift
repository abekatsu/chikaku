import Foundation

/// ビルド時に埋め込む設定。Android の `BuildConfig` に相当する。
///
/// 接続先は `Config/Chikaku.xcconfig` → `Info.plist` の `ChikakuServerBaseURL` 経由で入る。
/// ソースに直書きしないのは、開発機・本番で差し替えるものをコミットしないため。
enum Config {

    /// 初期値。ユーザーはペアリング画面の「詳細設定」から上書きできる。
    static let defaultServerBaseURL: String = {
        let value = Bundle.main.object(forInfoDictionaryKey: "ChikakuServerBaseURL") as? String
        guard let value, !value.isEmpty else {
            // xcconfig が未配置でもビルドは通したい。実行時に画面から入力できる。
            return "https://chikaku.example.com"
        }
        return value
    }()

    static var isDebugBuild: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    /// `BGTaskSchedulerPermittedIdentifiers`（Info.plist）と一致させること。
    enum BackgroundTask {
        /// 圏外復帰後のキュー掃き出し。
        static let upload = "com.damburisoft.chikaku.watch.upload"
        /// 動きがないときのヘルスチェック送信。
        static let heartbeat = "com.damburisoft.chikaku.watch.heartbeat"
    }
}
