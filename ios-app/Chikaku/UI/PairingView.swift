import SwiftUI

/// 招待コードの入力。サーバー側が大文字小文字・ハイフン・空白を無視して
/// 照合するため、ここでは見た目の正規化だけに留める。
struct PairingView: View {

    @Bindable var model: AppModel

    @State private var inviteCode = ""
    @State private var deviceName = ""
    @State private var serverURL = ""
    @State private var showAdvanced = false
    @FocusState private var focusedField: Field?

    private enum Field { case code, name, url }

    var body: some View {
        VStack(spacing: 0) {
            ScreenScaffold(title: S.pairingTitle) {
                Text(S.pairingBody)
                    .font(Theme.bodyFont)
                    .fixedSize(horizontal: false, vertical: true)

                VStack(alignment: .leading, spacing: 8) {
                    Text(S.pairingCodeLabel).font(Theme.captionFont).foregroundStyle(.secondary)
                    TextField("", text: $inviteCode)
                        .font(.system(size: 34, weight: .semibold, design: .monospaced))
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .textContentType(.oneTimeCode)
                        .focused($focusedField, equals: .code)
                        .padding(12)
                        .background(.quinary, in: RoundedRectangle(cornerRadius: 12))
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text(S.pairingNameLabel).font(Theme.captionFont).foregroundStyle(.secondary)
                    TextField("", text: $deviceName)
                        .font(Theme.bodyFont)
                        .autocorrectionDisabled()
                        .focused($focusedField, equals: .name)
                        .padding(12)
                        .background(.quinary, in: RoundedRectangle(cornerRadius: 12))
                }

                DisclosureGroup(isExpanded: $showAdvanced) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(S.pairingServerURLLabel)
                            .font(Theme.captionFont)
                            .foregroundStyle(.secondary)
                        TextField(model.settings.serverBaseURL, text: $serverURL)
                            .font(Theme.captionFont)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focusedField, equals: .url)
                            .padding(12)
                            .background(.quinary, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .padding(.top, 8)
                } label: {
                    Text(S.pairingAdvanced).font(Theme.captionFont)
                }

                if case .failed(let message) = model.pairing {
                    Text(message)
                        .font(Theme.bodyFont)
                        .foregroundStyle(Theme.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            PrimaryButton(
                title: isPairing ? S.pairingInProgress : S.pairingSubmit,
                enabled: !isPairing
            ) {
                focusedField = nil
                model.pair(
                    inviteCode: inviteCode,
                    deviceName: deviceName,
                    serverBaseURL: serverURL.isEmpty ? model.settings.serverBaseURL : serverURL
                )
            }
            .padding(Theme.screenPadding)
        }
        .onChange(of: inviteCode) { _, _ in
            if case .failed = model.pairing { model.dismissPairingError() }
        }
    }

    private var isPairing: Bool { model.pairing == .inProgress }
}
