import CoreLocation
import Foundation
import Observation

/// 測位の開始・停止と、OS からの位置更新の受け口。
///
/// Android の `LocationTrackingService`（Foreground Service）に相当するが、
/// iOS には常駐サービスも常駐通知も無い。代わりに **2つの測位サービスを重ねる**。
///
/// 1. **Significant Location Change（SLC）**
///    セル基地局ベースで、およそ 500m の移動か数分ごとにしか発火しない代わりに
///    消費電力がほぼ無視できる。そして最大の役目は、**アプリが終了させられても、
///    端末が再起動されても、OS がアプリを起こし直してくれる**こと。
///    Android の `BootReceiver` + `WatchdogWorker` の代わりがこれ。
///
/// 2. **標準の位置更新（`startUpdatingLocation`）**
///    `distanceFilter` 50m で、動いたときだけ細かい位置を配信させる。
///
/// `pausesLocationUpdatesAutomatically` は **false** にしている。true にすると
/// OS が「動きが無い」と判断して更新を止めるが、**再開は自動では保証されない**。
/// 家でじっとしている親が近所へ 200m 歩いた場合、SLC の発火閾値には届かず、
/// 止まったままの標準更新も戻らないため、見守りが静かに穴を開ける。
/// 静止中の消費は `distanceFilter` が既に抑えているので、止める必要がない。
@MainActor
@Observable
final class LocationTracker: NSObject, @preconcurrency CLLocationManagerDelegate {

    private let manager = CLLocationManager()
    private let repository: LocationRepository
    private let settings: SettingsStore

    /// 位置をキューに積んだ直後に呼ばれる。送信の起動は呼び出し側に任せる。
    var onQueued: (() -> Void)?

    private(set) var authorizationStatus: CLAuthorizationStatus = .notDetermined
    private(set) var accuracyAuthorization: CLAccuracyAuthorization = .fullAccuracy
    private(set) var isTracking = false

    init(repository: LocationRepository, settings: SettingsStore) {
        self.repository = repository
        self.settings = settings
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = LocationTuning.desiredAccuracy
        manager.distanceFilter = LocationTuning.distanceFilterMeters
        manager.activityType = LocationTuning.activityType
        manager.pausesLocationUpdatesAutomatically = false
        authorizationStatus = manager.authorizationStatus
        accuracyAuthorization = manager.accuracyAuthorization
    }

    // MARK: - 権限

    var hasAlwaysAuthorization: Bool { authorizationStatus == .authorizedAlways }

    var hasWhenInUseAuthorization: Bool {
        authorizationStatus == .authorizedAlways || authorizationStatus == .authorizedWhenInUse
    }

    var hasFullAccuracy: Bool { accuracyAuthorization == .fullAccuracy }

    func requestWhenInUseAuthorization() {
        manager.requestWhenInUseAuthorization()
    }

    /// iOS は「使用中のみ」を得たあとでないと「常に許可」を出さない。
    /// さらに、一度目のこの呼び出しでダイアログが出ずに保留されることがある
    /// （OS が後で自発的に尋ねる）。その場合は設定画面へ誘導するしかない。
    func requestAlwaysAuthorization() {
        manager.requestAlwaysAuthorization()
    }

    // MARK: - 開始・停止

    func start() {
        guard hasWhenInUseAuthorization else {
            Log.location.notice("権限が無いため開始できません")
            return
        }
        // バックグラウンドモードの宣言（Info.plist）が無い状態でこれを true にすると
        // 実行時に落ちる。宣言済みだが、権限が無い間は触らない。
        manager.allowsBackgroundLocationUpdates = hasAlwaysAuthorization
        manager.startUpdatingLocation()
        if hasAlwaysAuthorization {
            // 終了・再起動からの復帰はこれだけが担う。
            manager.startMonitoringSignificantLocationChanges()
        }
        isTracking = true
        Log.location.info("測位を開始しました (always=\(self.hasAlwaysAuthorization, privacy: .public))")
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        manager.allowsBackgroundLocationUpdates = false
        finishOneShot()
        isTracking = false
        Log.location.info("測位を停止しました")
    }

    /// 「今すぐ送信」とヘルスチェック用の単発測位。閾値を無視してキューに積み、
    /// **測位が終わる（あるいは失敗する）まで待つ**。
    ///
    /// 待たずに戻ると、取った点がキューに入る前に呼び出し元が送信を終えてしまう。
    /// バックグラウンドタスクではその直後にアプリが停止させられるため、
    /// ヘルスチェックそのものが落ちる。
    func oneShot() async {
        guard hasWhenInUseAuthorization else { return }

        pendingForcedRequest = true
        await withCheckedContinuation { continuation in
            // 既に要求が飛んでいるなら待ち手として並ぶだけにする。
            // `requestLocation` を重ねて呼ぶと先の要求が取り消される。
            let alreadyInFlight = !oneShotContinuations.isEmpty
            oneShotContinuations.append(continuation)
            if !alreadyInFlight {
                manager.requestLocation()
            }
        }
    }

    private var pendingForcedRequest = false
    private var oneShotContinuations: [CheckedContinuation<Void, Never>] = []

    /// 待っている全員を1回だけ再開する。`requestLocation` は成功か失敗の
    /// どちらかのデリゲートを必ず呼ぶので、その両方から通す。
    private func finishOneShot() {
        let waiting = oneShotContinuations
        oneShotContinuations.removeAll()
        for continuation in waiting {
            continuation.resume()
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationStatus = manager.authorizationStatus
        accuracyAuthorization = manager.accuracyAuthorization

        // 権限が「常に」へ昇格した瞬間に、SLC を張り直す必要がある。
        // ここを取りこぼすと、次にアプリが開かれるまで復帰能力を持たないまま走る。
        if settings.settings.trackingEnabled, hasWhenInUseAuthorization {
            start()
        } else if !hasWhenInUseAuthorization, isTracking {
            stop()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let force = pendingForcedRequest
        pendingForcedRequest = false
        finishOneShot()

        var queued = false
        for location in locations {
            if repository.onLocationUpdate(location, force: force) {
                queued = true
            }
        }
        // 積まなかった場合も送信は起こす。前回失敗して残っている分があるため。
        onQueued?()
        if queued {
            Log.location.debug("位置をキューに積みました")
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        pendingForcedRequest = false
        finishOneShot()
        // 測位できないだけなら次の機会に取れる。ここで停止はしない。
        Log.location.notice("測位に失敗しました: \(error.localizedDescription, privacy: .public)")
    }
}
