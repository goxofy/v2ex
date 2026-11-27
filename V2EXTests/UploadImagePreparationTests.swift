import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import V2EX

final class UploadImagePreparationTests: XCTestCase {
    func testSmallPNGIsAcceptedAndKeepsTransparency() async throws -> Void {
        let file = try fixture(type: .png, width: 2, height: 2)
        defer { try? FileManager.default.removeItem(at: file) }
        let prepared = try await UploadImagePreparation.shared.prepare(file)
        XCTAssertEqual(prepared.encoding, .png)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(prepared.data as CFData, nil))
        let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(image.width, 2)
        XCTAssertEqual(image.height, 2)
        XCTAssertTrue([CGImageAlphaInfo.last, .first, .premultipliedLast, .premultipliedFirst].contains(image.alphaInfo))
    }

    func testLargeImageIsOrientedDownsampledAndStrippedOfGPS() async throws -> Void {
        let gps: [CFString: Any] = [kCGImagePropertyGPSLatitude: 31.2, kCGImagePropertyGPSLatitudeRef: "N"]
        let file = try fixture(type: .jpeg, width: 5_000, height: 10, properties: [
            kCGImagePropertyGPSDictionary: gps, kCGImagePropertyOrientation: 6
        ])
        defer { try? FileManager.default.removeItem(at: file) }
        let original = try XCTUnwrap(CGImageSourceCreateWithURL(file as CFURL, nil))
        let originalProperties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(original, 0, nil) as? [CFString: Any])
        XCTAssertNotNil(originalProperties[kCGImagePropertyGPSDictionary])
        let prepared = try await UploadImagePreparation.shared.prepare(file)
        XCTAssertEqual(prepared.encoding, .jpeg)
        XCTAssertLessThanOrEqual(prepared.data.count, UploadImage.maximumBytes)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(prepared.data as CFData, nil))
        let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(image.height, 4_096)
        XCTAssertLessThan(image.width, image.height)
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        XCTAssertNil(properties[kCGImagePropertyGPSDictionary])
    }

    func testGIFIsRejectedInsteadOfSilentlyFlattened() async throws -> Void {
        let file = try fixture(type: .gif, width: 2, height: 2, frames: 2)
        defer { try? FileManager.default.removeItem(at: file) }
        do { _ = try await UploadImagePreparation.shared.prepare(file); XCTFail("Accepted animated GIF") }
        catch { XCTAssertEqual(error as? ImgurUploadError, .unsupportedImage) }
    }

    func testInvalidAndOversizedInputAreRejectedBeforeDecoding() async throws -> Void {
        let file = temporaryFile()
        defer { try? FileManager.default.removeItem(at: file) }
        try Data("not an image".utf8).write(to: file)
        do { _ = try await UploadImagePreparation.shared.prepare(file); XCTFail("Accepted invalid image") }
        catch { XCTAssertEqual(error as? ImgurUploadError, .unsupportedImage) }
        let handle = try FileHandle(forWritingTo: file)
        try handle.truncate(atOffset: 40 * 1_024 * 1_024 + 1)
        try handle.close()
        do { _ = try await UploadImagePreparation.shared.prepare(file); XCTFail("Accepted oversized input") }
        catch { XCTAssertEqual(error as? ImgurUploadError, .imageTooLarge) }
    }

    private func temporaryFile() -> URL {
        let root = ProcessInfo.processInfo.environment["CLAUDE_JOB_DIR"].map {
            URL(fileURLWithPath: $0).appendingPathComponent("tmp", isDirectory: true)
        } ?? FileManager.default.temporaryDirectory
        return root.appendingPathComponent("image-preparation-\(UUID().uuidString)")
    }

    private func fixture(type: UTType, width: Int, height: Int, frames: Int = 1, properties: [CFString: Any] = [:]) throws -> URL {
        let context = try XCTUnwrap(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(red: 0.2, green: 0.5, blue: 0.8, alpha: 0.5))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        let file = temporaryFile()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(file as CFURL, type.identifier as CFString, frames, nil))
        for _ in 0..<frames { CGImageDestinationAddImage(destination, image, properties as CFDictionary) }
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return file
    }
}
