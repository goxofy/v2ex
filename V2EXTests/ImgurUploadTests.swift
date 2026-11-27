import XCTest
@testable import V2EX

final class ImgurUploadTests: XCTestCase {
    private let image = UploadImage(data: Data([1, 2, 3]), encoding: .jpeg)
    private let raw = "https://i.imgur.com/v3lpwO1.jpeg"

    private func client(status: Int = 200, body: String, error: URLError? = nil) -> ImgurUploadClient {
        ImgurRequestProtocol.prepare(status: status, body: body, error: error)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ImgurRequestProtocol.self]
        config.httpAdditionalHeaders = ["Cookie": "V2EX-session", "Authorization": "Bearer V2EX-token"]
        return ImgurUploadClient(configuration: config)
    }

    func testMultipartRequestAndCredentialIsolation() async throws -> Void {
        let service = client(body: "{\"success\":true,\"data\":{\"link\":\"\(raw)\"}}")
        let url = try await service.upload(image, clientID: " client123 ")
        XCTAssertEqual(url.absoluteString, raw)
        let requests = ImgurRequestProtocol.requests
        XCTAssertEqual(requests.count, 1)
        let sent = try XCTUnwrap(requests.first)
        XCTAssertEqual(sent.url?.absoluteString, "https://api.imgur.com/3/image")
        XCTAssertEqual(sent.httpMethod, "POST")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "Authorization"), "Client-ID client123")
        XCTAssertNil(sent.value(forHTTPHeaderField: "Cookie"))
        let built = try ImgurUploadClient.request(image: image, clientID: "client123")
        XCTAssertFalse(built.httpShouldHandleCookies)
        let body = try XCTUnwrap(built.httpBody)
        XCTAssertTrue(body.range(of: Data("name=\"image\"; filename=\"image.jpeg\"".utf8)) != nil)
        XCTAssertTrue(body.range(of: Data("Content-Type: image/jpeg\r\n\r\n".utf8)) != nil)
        XCTAssertTrue(body.range(of: image.data) != nil)
        XCTAssertTrue(built.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("multipart/form-data; boundary=") == true)
    }

    func testInvalidConfigurationAndImageNeverStartRequests() async -> Void {
        for id in ["", "abc\r\nInjected: yes", "abc secret", String(repeating: "a", count: 129)] {
            let service = client(body: "{}")
            do { _ = try await service.upload(image, clientID: id); XCTFail("Accepted invalid ID") }
            catch { XCTAssertEqual(error as? ImgurUploadError, .invalidClientID) }
            XCTAssertTrue(ImgurRequestProtocol.requests.isEmpty)
        }
        for bytes in [Data(), Data(repeating: 0, count: UploadImage.maximumBytes + 1)] {
            let service = client(body: "{}")
            do { _ = try await service.upload(UploadImage(data: bytes, encoding: .png), clientID: "client123"); XCTFail("Accepted invalid image") }
            catch { XCTAssertNotNil(error as? ImgurUploadError) }
            XCTAssertTrue(ImgurRequestProtocol.requests.isEmpty)
        }
    }

    func testHTTPFailuresDoNotRetry() async -> Void {
        for (status, expected) in [(401, ImgurUploadError.invalidClientID), (403, .invalidClientID), (413, .imageTooLarge), (429, .rateLimited), (503, .httpStatus(503)), (302, .httpStatus(302))] {
            let service = client(status: status, body: "{}")
            do { _ = try await service.upload(image, clientID: "client123"); XCTFail("Accepted HTTP \(status)") }
            catch { XCTAssertEqual(error as? ImgurUploadError, expected) }
            XCTAssertEqual(ImgurRequestProtocol.requests.count, 1)
        }
    }

    func testOnlySuccessfulOriginalImageResponseIsInserted() async -> Void {
        for body in [
            "not JSON", "{}", "{\"success\":true,\"data\":{}}",
            "{\"success\":false,\"data\":{\"link\":\"\(raw)\"}}",
            "{\"success\":true,\"data\":{\"link\":\"https://imgur.com/gallery/abc\"}}",
            "{\"success\":true,\"data\":{\"link\":\"http://i.imgur.com/abc.jpg\"}}"
        ] {
            let service = client(body: body)
            do { _ = try await service.upload(image, clientID: "client123"); XCTFail("Accepted \(body)") }
            catch { XCTAssertEqual(error as? ImgurUploadError, .invalidResponse) }
            XCTAssertEqual(ImgurRequestProtocol.requests.count, 1)
        }
    }

    func testTransportFailureAndCancellationDoNotRetry() async -> Void {
        for code in [URLError.timedOut, .notConnectedToInternet, .cancelled] {
            let service = client(body: "", error: URLError(code))
            do { _ = try await service.upload(image, clientID: "client123"); XCTFail("Unexpected success") }
            catch {
                if code == .cancelled { XCTAssertTrue(error is CancellationError) }
                else { XCTAssertEqual(error as? ImgurUploadError, code == .timedOut ? .timedOut : .network) }
            }
            XCTAssertEqual(ImgurRequestProtocol.requests.count, 1)
        }
    }
}

private final class ImgurRequestProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var storedRequests: [URLRequest] = []
    private static var status = 200
    private static var body = Data()
    private static var error: URLError?
    static var requests: [URLRequest] { lock.withLock { storedRequests } }

    static func prepare(status: Int, body: String, error: URLError?) -> Void {
        lock.withLock {
            storedRequests = []
            self.status = status
            self.body = Data(body.utf8)
            self.error = error
        }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() -> Void {
        let response = Self.lock.withLock { () -> (Int, Data, URLError?) in
            Self.storedRequests.append(request)
            return (Self.status, Self.body, Self.error)
        }
        if let error = response.2 { client?.urlProtocol(self, didFailWithError: error); return }
        let http = HTTPURLResponse(url: request.url!, statusCode: response.0, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: response.1)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() -> Void {}
}
