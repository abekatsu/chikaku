import SwiftUI

/// プロミネントディスクロージャー（同意画面）。
/// 「見守られていること」を親本人が認識・同意したうえで始める（CLAUDE.md §5）。
struct DisclosureView: View {

    let onAgree: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScreenScaffold(title: S.disclosureTitle) {
                Text(S.disclosureBody)
                    .font(Theme.bodyFont)
                    .fixedSize(horizontal: false, vertical: true)

                VStack(alignment: .leading, spacing: 16) {
                    BulletRow(systemImage: "person.2.fill", text: S.disclosurePointWho)
                    BulletRow(systemImage: "location.fill", text: S.disclosurePointWhen)
                    BulletRow(systemImage: "hand.raised.fill", text: S.disclosurePointStop)
                }
                .padding(.vertical, 8)
            }

            PrimaryButton(title: S.disclosureAgree, action: onAgree)
                .padding(Theme.screenPadding)
        }
    }
}
