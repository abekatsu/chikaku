import Foundation

/// 送信結果。呼び出し側は「捨てる」「後でやり直す」「ペアリングし直す」を
/// 区別する必要があるため、単なる成否ではなく理由まで返す（Android の `ApiResult` と対）。
enum ApiResult<T> {
    case success(T)
    /// 通信できなかった（圏外・DNS失敗・タイムアウト等）。時間をおいて再試行する。
    case networkError(any Error)
    /// サーバーが 5xx / 408 / 429 を返した。再試行する。
    case serverError(code: Int, body: String?)
    /// 認証が通らない。ペアリングをやり直す必要がある。
    case unauthorized
    /// リクエスト自体が不正（4xx）。再試行しても無駄なので捨てる。
    case clientError(code: Int, message: String?)
}

/// サーバーとの通信。`URLSession` を直接使う（依存ライブラリを増やさない）。
struct ApiClient: Sendable {

    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 60
        // 圏外での即時失敗を待たない。再送はこちらのキューが受け持つ。
        config.waitsForConnectivity = false
        config.allowsCellularAccess = true
        // 位置情報が本文に含まれるため、URL キャッシュには残さない。
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        self.session = URLSession(configuration: config)
    }

    func registerDevice(
        baseURL: String,
        inviteCode: String,
        deviceName: String,
        deviceModel: String
    ) async -> ApiResult<RegisterDeviceResponse> {
        guard let url = resolve(baseURL, path: "api/v1/devices/register") else {
            return .clientError(code: 0, message: "サーバーURLの形式が正しくありません")
        }
        let body = RegisterDeviceRequest(
            inviteCode: inviteCode,
            deviceName: deviceName,
            deviceModel: deviceModel
        )
        guard let request = makeRequest(url: url, body: body, token: nil) else {
            return .clientError(code: 0, message: "リクエストを作成できませんでした")
        }
        return await execute(request) { data in
            try JSONDecoder().decode(RegisterDeviceResponse.self, from: data)
        }
    }

    /// 位置情報を1件送る。まとめ送りにしないのは、サーバー側の契約
    /// （CLAUDE.md §3.2 の POST /api/v1/location）を単純に保つため。
    /// キューの消化は `Uploader` がこれを繰り返して行う。
    func postLocation(
        baseURL: String,
        token: String,
        payload: LocationPayload
    ) async -> ApiResult<Void> {
        guard let url = resolve(baseURL, path: "api/v1/location") else {
            return .clientError(code: 0, message: "サーバーURLの形式が正しくありません")
        }
        guard let request = makeRequest(url: url, body: payload, token: token) else {
            return .clientError(code: 0, message: "リクエストを作成できませんでした")
        }
        return await execute(request) { _ in () }
    }

    // MARK: - 内部

    private func makeRequest(url: URL, body: some Encodable, token: String?) -> URLRequest? {
        guard let data = try? JSONEncoder().encode(body) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = data
        return request
    }

    private func execute<T>(
        _ request: URLRequest,
        parse: @Sendable (Data) throws -> T
    ) async -> ApiResult<T> {
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                return .clientError(code: 0, message: "応答を解釈できませんでした")
            }
            let text = String(data: data, encoding: .utf8)

            switch http.statusCode {
            case 200..<300:
                do {
                    return .success(try parse(data))
                } catch {
                    return .clientError(
                        code: http.statusCode,
                        message: "応答を解釈できませんでした: \(error.localizedDescription)"
                    )
                }
            case 401, 403:
                return .unauthorized
            // 408/429 は一時的なものなので再試行対象に寄せる（サーバー §4.5 のレート制限）。
            case 408, 429:
                return .serverError(code: http.statusCode, body: text)
            case 500...:
                return .serverError(code: http.statusCode, body: text)
            default:
                return .clientError(
                    code: http.statusCode,
                    message: errorMessage(data) ?? text.map { String($0.prefix(200)) }
                )
            }
        } catch {
            return .networkError(error)
        }
    }

    private func errorMessage(_ data: Data) -> String? {
        guard let decoded = try? JSONDecoder().decode(ErrorResponse.self, from: data) else {
            return nil
        }
        return decoded.message ?? decoded.error
    }
}

/// ベースURLに末尾スラッシュがあってもなくても同じ結果になるよう相対パスを解決する。
/// TLS必須（CLAUDE.md §5）のため https 以外は debug ビルドでのみ許可する。
func resolve(_ baseURL: String, path: String) -> URL? {
    let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return nil }
    let normalized = trimmed.hasSuffix("/") ? trimmed : trimmed + "/"
    guard let base = URL(string: normalized), let scheme = base.scheme?.lowercased() else {
        return nil
    }
    guard scheme == "https" || Config.isDebugBuild else { return nil }
    return URL(string: path, relativeTo: base)?.absoluteURL
}
