import CoreLocation
import Foundation

/// バッテリー最優先の測位パラメータ（CLAUDE.md §2.2）。
/// Android の `LocationTuning.kt` と対だが、iOS には更新間隔の概念が無いため
/// 「間隔」ではなく「どの測位サービスを使うか」で省電力を作る。
enum LocationTuning {

    /// これ未満しか動いていない位置更新はそもそも配信されない。
    /// Android の `setMinUpdateDistanceMeters` に相当する唯一のつまみ。
    static let distanceFilterMeters: CLLocationDistance = 50

    /// 前回キューに入れた位置からこれ以上離れたら送信対象にする。
    static let sendDistanceThresholdMeters: CLLocationDistance = 50

    /// 動きがなくても、この間隔で1回は送る。
    /// 「送られてこない」のが静止なのか異常なのかを子側が区別できるようにするため。
    static let heartbeatInterval: TimeInterval = 30 * 60

    /// 精度がこれより悪い測位結果は誤差が大きすぎるため捨てる。
    static let maxAcceptableAccuracyMeters: CLLocationDistance = 500

    /// 測位時刻がこれより古い結果は使わない。CoreLocation は起動直後に
    /// キャッシュ済みの古い位置を1回返してくることがあり、それを「今いる場所」
    /// として送ると子側に嘘を見せる。
    static let maxLocationAgeSeconds: TimeInterval = 5 * 60

    /// キューに残せる最大件数。長期圏外でも無限に膨らませない。
    static let maxQueueSize = 500

    /// 1件あたりの送信試行上限。超えたら捨てる。
    static let maxSendAttempts = 10

    /// GPS 単独を避け Wi-Fi / セル測位を使う。Android の
    /// `PRIORITY_BALANCED_POWER_ACCURACY` に相当する。
    static let desiredAccuracy = kCLLocationAccuracyHundredMeters

    /// 歩行者の見守りが用途。ナビゲーション用の頻繁な測位を要求しない。
    static let activityType: CLActivityType = .other
}
