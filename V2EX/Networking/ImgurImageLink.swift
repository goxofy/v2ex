import Foundation

/// Only original image files are accepted; Imgur pages and albums are not images.
enum ImgurImageLink {
    static func url(from raw: String) -> URL? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parts = URLComponents(string: value),
              parts.scheme?.lowercased() == "https",
              parts.host?.lowercased() == "i.imgur.com",
              parts.user == nil, parts.password == nil, parts.port == nil,
              parts.query == nil, parts.fragment == nil,
              parts.percentEncodedPath.range(
                of: #"^/[A-Za-z0-9]+\.(?:jpe?g|png|gif|webp)$"#,
                options: [.regularExpression, .caseInsensitive]
              ) != nil else { return nil }
        return parts.url
    }
}
