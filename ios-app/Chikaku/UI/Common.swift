import SwiftUI

/// 画面共通の部品。Android の `ui/Common.kt` と対。

struct PrimaryButton: View {
    let title: String
    var enabled: Bool = true
    var tint: Color = Theme.accent
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Theme.sectionFont)
                .frame(maxWidth: .infinity, minHeight: Theme.buttonMinHeight)
        }
        .buttonStyle(.borderedProminent)
        .tint(tint)
        .disabled(!enabled)
    }
}

struct SecondaryButton: View {
    let title: String
    var tint: Color = Theme.accent
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Theme.bodyFont)
                .frame(maxWidth: .infinity, minHeight: Theme.buttonMinHeight)
        }
        .buttonStyle(.bordered)
        .tint(tint)
    }
}

/// 「・」付きの要点。同意画面で使う。
struct BulletRow: View {
    let systemImage: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 24))
                .foregroundStyle(Theme.accent)
                .frame(width: 32)
            Text(text)
                .font(Theme.bodyFont)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// 「項目名 / 値」の1行。状態画面で使う。
struct StatusRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(Theme.captionFont)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(Theme.bodyFont)
                .multilineTextAlignment(.trailing)
        }
    }
}

/// 画面の外枠。縦スクロール + 余白を揃える。
struct ScreenScaffold<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.itemSpacing) {
                Text(title)
                    .font(Theme.titleFont)
                    .padding(.bottom, 4)
                content()
            }
            .padding(Theme.screenPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
