import Foundation
import Security

/// 端末トークン専用の小さな Keychain ラッパ。
///
/// `UserDefaults` に置かないのは、あれが平文の plist でバックアップにも含まれるため。
/// Android 側で DataStore をバックアップ対象から外しているのと同じ意図（CLAUDE.md §5）。
enum Keychain {

    /// アクセス条件の選び方には理由がある。
    ///
    /// - `AfterFirstUnlock`: **`WhenUnlocked` にしてはいけない。** 位置情報による
    ///   バックグラウンド起動は画面ロック中にも起きる。`WhenUnlocked` だと再起動後に
    ///   親が一度も画面を開くまで送信が全部失敗し、しかも原因が見えない。
    /// - `ThisDeviceOnly`: バックアップ復元や端末間転送でトークンが別の端末に
    ///   複製されるのを防ぐ。機種変更時はペアリングをやり直す。
    private static var accessibility: CFString { kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly }

    private static let service = "com.damburisoft.chikaku.watch"

    static func set(_ value: String, for account: String) {
        let data = Data(value.utf8)
        // 上書きのために一度消す（SecItemUpdate は不在時に失敗するので分岐が増えるだけ）。
        remove(account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: accessibility,
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    static func get(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    static func remove(_ account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
