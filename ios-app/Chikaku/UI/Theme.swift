import SwiftUI

/// 高齢者向けの寸法。Android の `ui/theme/Theme.kt` と同じ方針で、
/// 本文 20pt・見出し 24〜30pt・主要ボタンは最小高 64pt を守る。
enum Theme {
    static let bodyFont = Font.system(size: 20)
    static let titleFont = Font.system(size: 30, weight: .bold)
    static let sectionFont = Font.system(size: 24, weight: .semibold)
    static let captionFont = Font.system(size: 17)

    static let buttonMinHeight: CGFloat = 64
    static let screenPadding: CGFloat = 24
    static let itemSpacing: CGFloat = 20

    static let accent = Color(red: 0.09, green: 0.42, blue: 0.71)
    static let danger = Color(red: 0.72, green: 0.18, blue: 0.18)
    static let ok = Color(red: 0.13, green: 0.50, blue: 0.26)
}
