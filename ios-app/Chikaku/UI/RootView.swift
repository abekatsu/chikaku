import SwiftUI

/// 画面ルーティング。Android の `MainActivity` と同じ順序で進む。
/// 同意 → ペアリング → 権限 → 状態画面。
struct RootView: View {

    @State private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    init(graph: Graph) {
        _model = State(initialValue: AppModel(graph: graph))
    }

    var body: some View {
        Group {
            if !model.settings.consented {
                DisclosureView(onAgree: model.onConsent)
            } else if !model.settings.isPaired {
                PairingView(model: model)
            } else if !canStartTracking {
                PermissionView(model: model)
            } else {
                StatusView(model: model)
            }
        }
        .onChange(of: scenePhase) { _, phase in
            // 設定画面から戻ってきたときに権限と件数を取り直す。
            if phase == .active { model.refresh() }
        }
    }

    /// 「常に許可」と「正確な位置情報」が揃って初めて設計どおりに動く。
    /// 通知は見守り自体には要らないので、ここでは条件にしない。
    private var canStartTracking: Bool {
        model.tracker.hasAlwaysAuthorization && model.tracker.hasFullAccuracy
    }
}
