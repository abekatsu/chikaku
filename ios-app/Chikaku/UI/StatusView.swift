import SwiftUI

/// 定常状態の画面。出す情報は「最終送信」「未送信件数」「端末名」だけに絞る。
/// 親が毎日見る画面なので、判断を迫る要素を増やさない。
struct StatusView: View {

    @Bindable var model: AppModel

    @State private var confirmingStop = false
    @State private var confirmingUnpair = false

    var body: some View {
        VStack(spacing: 0) {
            ScreenScaffold(title: isTracking ? S.statusTitle : S.statusTitleStopped) {

                // 見出しで既に状態は言っているので、ここは繰り返さず
                // 「それが何を意味するか」を書く。
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: isTracking ? "location.fill" : "location.slash")
                        .font(.system(size: 34))
                        .foregroundStyle(isTracking ? Theme.ok : Theme.danger)
                    Text(isTracking ? S.statusRunningDetail : S.statusStoppedDetail)
                        .font(Theme.bodyFont)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.bottom, 4)

                VStack(spacing: 16) {
                    StatusRow(label: S.statusLastSent, value: lastSentText)
                    Divider()
                    StatusRow(label: S.statusPending, value: S.statusPendingCount(model.pendingCount))
                    Divider()
                    StatusRow(label: S.statusDeviceName, value: model.settings.deviceName ?? "—")
                }
                .padding(20)
                .background(.quinary, in: RoundedRectangle(cornerRadius: 16))
            }

            VStack(spacing: 12) {
                if isTracking {
                    PrimaryButton(title: S.statusSendNow, action: model.sendNow)
                    SecondaryButton(title: S.statusStop, tint: Theme.danger) {
                        confirmingStop = true
                    }
                } else {
                    PrimaryButton(title: S.statusStart, action: model.startTracking)
                }
                Button(S.statusUnpair) { confirmingUnpair = true }
                    .font(Theme.captionFont)
                    .foregroundStyle(Theme.danger)
                    .frame(minHeight: 44)
            }
            .padding(Theme.screenPadding)
        }
        .confirmationDialog(
            S.statusStopConfirmTitle,
            isPresented: $confirmingStop,
            titleVisibility: .visible
        ) {
            Button(S.statusStopConfirmOK, role: .destructive) { model.stopTracking() }
            Button(S.commonCancel, role: .cancel) {}
        } message: {
            Text(S.statusStopConfirmBody)
        }
        .confirmationDialog(
            S.statusUnpairConfirmTitle,
            isPresented: $confirmingUnpair,
            titleVisibility: .visible
        ) {
            Button(S.statusUnpair, role: .destructive) { model.unpair() }
            Button(S.commonCancel, role: .cancel) {}
        } message: {
            Text(S.statusUnpairConfirmBody)
        }
    }

    private var isTracking: Bool { model.settings.trackingEnabled }

    private var lastSentText: String {
        guard let lastSentAt = model.settings.lastSentAt else { return S.statusLastSentNever }
        return Self.formatter.string(from: lastSentAt)
    }

    private static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ja_JP")
        formatter.dateFormat = "M月d日 HH:mm"
        return formatter
    }()
}
