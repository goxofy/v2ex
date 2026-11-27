import CoreTransferable
import Foundation
import ImageIO
import UniformTypeIdentifiers

struct PickedUploadImage: Transferable, Sendable {
    let image: UploadImage

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { file in
            // Process within the import callback, while the provider's file is valid.
            PickedUploadImage(image: try await UploadImagePreparation.shared.prepare(file.file))
        }
    }
}

actor UploadImagePreparation {
    static let shared = UploadImagePreparation()
    private let maximumInputBytes = 40 * 1_024 * 1_024

    func prepare(_ file: URL) throws -> UploadImage {
        try Task.checkCancellation()
        guard file.isFileURL else { throw ImgurUploadError.unsupportedImage }
        // URL resource values may be cached by a provider before the file changes.
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        guard size > 0, size <= Int64(maximumInputBytes) else { throw ImgurUploadError.imageTooLarge }
        guard let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let identifier = CGImageSourceGetType(source) as String?,
              let type = UTType(identifier),
              type.conforms(to: .jpeg) || type.conforms(to: .png) || type.conforms(to: .heic) || type.conforms(to: .heif),
              CGImageSourceGetCount(source) == 1,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 60_000, height <= 60_000,
              Double(width) * Double(height) <= 120_000_000
        else { throw ImgurUploadError.unsupportedImage }
        let encoding: UploadImage.Encoding = type.conforms(to: .png) ? .png : .jpeg
        var maximumDimension = min(4_096, max(width, height))
        while maximumDimension > 0 {
            try Task.checkCancellation()
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: maximumDimension
            ]
            guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
                throw ImgurUploadError.unsupportedImage
            }
            let data = NSMutableData()
            let outputType = encoding == .png ? UTType.png : .jpeg
            guard let destination = CGImageDestinationCreateWithData(data, outputType.identifier as CFString, 1, nil) else {
                throw ImgurUploadError.unsupportedImage
            }
            // A fresh image destination, without source properties, drops EXIF/GPS.
            CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.86] as CFDictionary)
            guard CGImageDestinationFinalize(destination) else { throw ImgurUploadError.unsupportedImage }
            try Task.checkCancellation()
            if data.length <= UploadImage.maximumBytes {
                return UploadImage(data: data as Data, encoding: encoding)
            }
            maximumDimension = maximumDimension * 3 / 4
        }
        throw ImgurUploadError.imageTooLarge
    }
}
