import Combine
import Foundation

@MainActor
final class ImageUploadCoordinator: ObservableObject {
    @Published private(set) var isUploading = false
    @Published var error: String?
    private(set) var context: String?
    private var generation = UUID()
    private(set) var task: Task<Void, Never>?
    private let client: any ImgurUploading

    init(client: any ImgurUploading = ImgurUploadClient()) {
        self.client = client
    }

    func setContext(_ value: String?) -> Void {
        guard context != value else { return }
        cancel()
        context = value
    }

    func start(
        context expectedContext: String,
        clientID: String,
        prepare: @escaping @MainActor () async throws -> UploadImage,
        onUploaded: @escaping @MainActor (URL) -> Void
    ) -> Void {
        guard context == expectedContext, !isUploading else { return }
        generation = UUID()
        let token = generation
        error = nil
        isUploading = true
        task = Task { [weak self, client] in
            do {
                let image = try await prepare()
                try Task.checkCancellation()
                let url = try await client.upload(image, clientID: clientID)
                try Task.checkCancellation()
                guard let self, self.generation == token, self.context == expectedContext else { return }
                self.isUploading = false
                self.task = nil
                onUploaded(url)
            } catch {
                guard let self, self.generation == token, self.context == expectedContext else { return }
                self.isUploading = false
                self.task = nil
                if !(error is CancellationError) && !Task.isCancelled {
                    self.error = (error as? ImgurUploadError)?.errorDescription
                        ?? "无法读取或上传图片，请重新选择。正文未修改。"
                }
            }
        }
    }

    func cancel() -> Void {
        generation = UUID()
        task?.cancel()
        task = nil
        isUploading = false
        error = nil
    }

    deinit { task?.cancel() }
}
