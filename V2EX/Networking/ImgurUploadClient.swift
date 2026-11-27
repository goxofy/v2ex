import Foundation

struct UploadImage: Sendable {
    enum Encoding: String, Sendable {
        case jpeg, png
        var mimeType: String { "image/\(rawValue)" }
    }

    static let maximumBytes = 10 * 1_024 * 1_024
    let data: Data
    let encoding: Encoding
}

enum ImgurUploadError: LocalizedError, Equatable {
    case invalidClientID
    case imageTooLarge
    case unsupportedImage
    case invalidResponse
    case rateLimited
    case httpStatus(Int)
    case network
    case timedOut

    var errorDescription: String? {
        switch self {
        case .invalidClientID: return "请在图片上传设置中填写有效的 Imgur Client ID。"
        case .imageTooLarge: return "图片过大，请选择较小的图片（上传文件最大 10 MiB）。"
        case .unsupportedImage: return "无法处理这张图片，请选择静态 JPEG、PNG 或 HEIC 图片。"
        case .invalidResponse: return "Imgur 未返回有效的原图直链，正文未修改。"
        case .rateLimited: return "Imgur 请求次数已达上限，请稍后再试。"
        case .httpStatus(let status): return "Imgur 上传未成功（HTTP \(status)），正文未修改。"
        case .network: return "无法确认图片上传结果，请检查网络。图片可能已上传，但正文未修改。"
        case .timedOut: return "图片上传超时。图片可能已上传，但正文未修改，请勿连续重试。"
        }
    }
}

protocol ImgurUploading: Sendable {
    func upload(_ image: UploadImage, clientID: String) async throws -> URL
}

struct ImgurUploadClient: ImgurUploading {
    private struct Envelope: Decodable {
        struct ImageData: Decodable { let link: String? }
        let success: Bool
        let data: ImageData?
    }

    private static let endpoint = URL(string: "https://api.imgur.com/3/image")!
    private static let isolatedSession = makeSession(configuration: .ephemeral)
    private let session: URLSession

    init(configuration: URLSessionConfiguration? = nil) {
        session = configuration.map { Self.makeSession(configuration: $0) } ?? Self.isolatedSession
    }

    private static func makeSession(configuration: URLSessionConfiguration) -> URLSession {
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.httpAdditionalHeaders = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 90
        return URLSession(configuration: configuration, delegate: ImgurRedirectBlocker(), delegateQueue: nil)
    }

    static func normalizedClientID(_ raw: String) -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, value.utf8.count <= 128,
              value.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) })
        else { return nil }
        return value
    }

    func upload(_ image: UploadImage, clientID: String) async throws -> URL {
        try Task.checkCancellation()
        let request = try Self.request(image: image, clientID: clientID)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            throw error.code == .timedOut ? ImgurUploadError.timedOut : ImgurUploadError.network
        }
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else { throw ImgurUploadError.invalidResponse }
        switch http.statusCode {
        case 200...299: break
        case 401, 403: throw ImgurUploadError.invalidClientID
        case 413: throw ImgurUploadError.imageTooLarge
        case 429: throw ImgurUploadError.rateLimited
        default: throw ImgurUploadError.httpStatus(http.statusCode)
        }
        guard data.count <= 1_024 * 1_024,
              let envelope = try? JSONDecoder().decode(Envelope.self, from: data),
              envelope.success, let link = envelope.data?.link,
              let url = ImgurImageLink.url(from: link)
        else { throw ImgurUploadError.invalidResponse }
        return url
    }

    static func request(image: UploadImage, clientID: String) throws -> URLRequest {
        guard let clientID = normalizedClientID(clientID) else { throw ImgurUploadError.invalidClientID }
        guard !image.data.isEmpty else { throw ImgurUploadError.unsupportedImage }
        guard image.data.count <= UploadImage.maximumBytes else { throw ImgurUploadError.imageTooLarge }
        let boundary = "V2EXImage-\(UUID().uuidString)"
        var body = Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"image\"; filename=\"image.\(image.encoding.rawValue)\"\r\nContent-Type: \(image.encoding.mimeType)\r\n\r\n".utf8)
        body.append(image.data)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.httpShouldHandleCookies = false
        request.setValue("Client-ID \(clientID)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        return request
    }
}

private final class ImgurRedirectBlocker: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) -> Void {
        completionHandler(nil)
    }
}
