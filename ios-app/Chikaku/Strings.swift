import Foundation

/// 画面に出す文言。Android の `res/values/strings.xml` と一対一で対応する。
///
/// 日本語単一言語のため String Catalog は使わない。文言を変えるときは
/// Android 側の `strings.xml` と揃えること（親から見て2機種で言い方が違うと混乱する）。
enum S {

    // MARK: - 説明画面（プロミネントディスクロージャー）

    static let disclosureTitle = "このアプリについて"
    static let disclosureBody = """
        このアプリは、あなたの現在地をご家族（お子さん）に知らせるためのものです。

        アプリを閉じている間や画面が消えている間も、バックグラウンドで位置情報を取得します。取得した位置は、ご家族だけが見られる専用のサーバーに送られます。

        電池を節約するため、位置が大きく動いたときだけ送信します。じっとしている間はほとんど通信しません。
        """
    static let disclosurePointWho = "見られるのは、登録されたご家族だけです"
    static let disclosurePointWhen = "アプリを閉じている間も位置を取得します"
    static let disclosurePointStop = "いつでもこのアプリから停止できます"
    static let disclosureAgree = "同意して次へ"

    // MARK: - ペアリング

    static let pairingTitle = "家族とつなぐ"
    static let pairingBody = "お子さんから伝えられた「招待コード」を入力してください。"
    static let pairingCodeLabel = "招待コード"
    static let pairingNameLabel = "この端末の名前（例: お父さんのiPhone）"
    static let pairingSubmit = "つなぐ"
    static let pairingInProgress = "接続しています…"
    static let pairingErrorEmptyCode = "招待コードを入力してください。"
    static func pairingErrorFailed(_ message: String) -> String { "つなげませんでした: \(message)" }
    static let pairingErrorNetwork = "インターネットにつながっていないようです。電波の良い場所でもう一度お試しください。"
    static func pairingErrorServer(_ code: Int) -> String {
        "サーバーが応答しませんでした（\(code)）。しばらくしてからお試しください。"
    }
    static let pairingErrorInvalidCode = "招待コードが正しくないか、期限が切れています。"
    static let pairingAdvanced = "詳細設定（サーバーURL）"
    static let pairingServerURLLabel = "サーバーURL"

    // MARK: - 権限

    static let permissionTitle = "許可のお願い"
    static let permissionStepWhenInUseTitle = "位置情報の許可"
    static let permissionStepWhenInUseBody =
        "まず、このアプリが位置情報を使えるようにします。次の画面で「Appの使用中は許可」を選んでください。"
    static let permissionStepAlwaysTitle = "「常に許可」への変更"
    static let permissionStepAlwaysBody =
        "アプリを閉じている間も見守るために、「常に許可」に変更してください。"
    static let permissionStepPreciseTitle = "正確な位置情報"
    static let permissionStepPreciseBody =
        "おおよその位置しか送られない設定になっています。正確な居場所をお知らせするために「正確な位置情報」を入にしてください。"
    static let permissionStepNotificationTitle = "通知の許可"
    static let permissionStepNotificationBody =
        "見守りが止まってしまったときにお知らせします。"
    static let permissionGrant = "許可する"
    static let permissionOpenSettings = "設定を開く"
    static let permissionGranted = "許可済み"
    static let permissionStart = "見守りを開始する"
    static let permissionDenied =
        "位置情報が許可されていません。「設定」→「みまもり」→「位置情報」から「常に」を選んでください。"

    // MARK: - 状態画面

    static let statusTitle = "見守り中です"
    static let statusRunningDetail = "位置が変わったときにご家族へお知らせしています。"
    static let statusStoppedDetail = "いまはご家族が居場所を確認できません。"
    static let statusTitleStopped = "停止しています"
    static let statusLastSent = "最終送信"
    static let statusLastSentNever = "まだ送信していません"
    static let statusPending = "未送信"
    static func statusPendingCount(_ count: Int) -> String { "\(count) 件" }
    static let statusDeviceName = "端末名"
    static let statusStop = "見守りを停止する"
    static let statusStart = "見守りを開始する"
    static let statusSendNow = "今すぐ送信する"
    static let statusStopConfirmTitle = "見守りを停止しますか？"
    static let statusStopConfirmBody = "停止するとご家族はあなたの居場所を確認できなくなります。"
    static let statusStopConfirmOK = "停止する"
    static let commonCancel = "キャンセル"
    static let statusUnpair = "家族との接続を解除する"
    static let statusUnpairConfirmTitle = "家族との接続を解除しますか？"
    static let statusUnpairConfirmBody =
        "接続を解除すると、保存されている端末情報と未送信の位置情報が削除されます。再開するには招待コードの入力が必要です。"

    // MARK: - 通知（見守りが止まったときだけ出す）

    static let notificationStoppedTitle = "見守りが止まっています"
    static let notificationStoppedBody = "アプリを開いて、位置情報の許可をご確認ください。"
}
