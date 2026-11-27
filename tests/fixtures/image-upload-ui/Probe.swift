import SwiftUI

@MainActor final class AppSettings: ObservableObject {
    @Published var imgurClientID = "abc123"
}
enum Theme {
    static let accent: Color = .green
    static let inset: Color = .gray.opacity(0.15)
    static let muted: Color = .secondary
    static let canvas: Color = .white
    enum Metric { static let screenPadding: CGFloat = 16 }
}
struct GroupHeader: View {
    let title: String
    var body: some View { Text(title) }
}
struct CardSection<Content: View>: View {
    let content: Content
    init(padding: CGFloat = 16, @ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View { content }
}
actor ProbeClient: ImgurUploading {
    private var pending: CheckedContinuation<URL, Never>?
    func upload(_ image: UploadImage, clientID: String) async throws -> URL {
        await withCheckedContinuation { pending = $0 }
    }
    func complete() -> Void {
        pending?.resume(returning: URL(string: "https://i.imgur.com/test.jpeg")!)
        pending = nil
    }
}
@main struct ProbeApp: App {
    @StateObject private var settings = AppSettings()
    var body: some Scene { WindowGroup { ProbeView().environmentObject(settings) } }
}
struct ProbeView: View {
    private let client: ProbeClient
    @StateObject private var upload: ImageUploadCoordinator
    @StateObject private var selection = ImageEditorSelection()
    @State private var text = "ab😀cd"
    @FocusState private var focused: Bool
    private let useEditor = ProcessInfo.processInfo.arguments.contains("--editor")
    init() {
        let client = ProbeClient()
        self.client = client
        _upload = StateObject(wrappedValue: ImageUploadCoordinator(client: client))
    }
    var body: some View {
        VStack(spacing: 16) {
            if useEditor {
                TextEditor(text: $text, selection: $selection.value)
                    .focused($focused).frame(height: 110).accessibilityIdentifier("body")
            } else {
                TextField("Reply", text: $text, selection: $selection.value, axis: .vertical)
                    .focused($focused).accessibilityIdentifier("body")
            }
            Text(text).accessibilityIdentifier("result")
            Text(upload.context ?? "inactive").accessibilityIdentifier("context")
            Text(upload.isUploading ? "uploading" : "idle").accessibilityIdentifier("state")
            Button("Select middle") {
                let index = text.index(text.startIndex, offsetBy: 2)
                selection.value = TextSelection(range: index..<index)
                focused = true
            }.accessibilityIdentifier("middle")
            Button("Mock upload") {
                selection.record(in: text)
                upload.start(context: "probe", clientID: "abc123", prepare: {
                    UploadImage(data: Data([1]), encoding: .jpeg)
                }, onUploaded: insert)
            }.accessibilityIdentifier("mock")
            Button("Complete upload") { Task { await client.complete() } }.accessibilityIdentifier("complete")
            ImageUploadButton(upload: upload, context: "probe", onPrepareSelection: {
                selection.record(in: text)
            }, onUploaded: insert).accessibilityIdentifier("picker")
        }
        .padding(20)
        .onChange(of: selection.value) { _, _ in selection.record(in: text) }
    }
    private func insert(_ url: URL) -> Void {
        selection.insert(url, into: $text)
        focused = true
    }
}
