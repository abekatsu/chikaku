import Foundation
import os

/// ログ出力口。
///
/// **位置情報そのものは絶対に出さない。** Android 側で HTTP ログを debug ビルドに
/// 限定しているのと同じ理由（CLAUDE.md §5）。`os.Logger` の既定は `private` で
/// 値が伏せられるが、ここでは伏せられる前提に寄りかからず、そもそも渡さない。
enum Log {
    private static let subsystem = "com.damburisoft.chikaku.watch"

    static let location = Logger(subsystem: subsystem, category: "location")
    static let upload = Logger(subsystem: subsystem, category: "upload")
    static let queue = Logger(subsystem: subsystem, category: "queue")
    static let lifecycle = Logger(subsystem: subsystem, category: "lifecycle")
}
