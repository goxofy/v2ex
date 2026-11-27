import PhotosUI
import SwiftUI

struct ImageUploadButton: View {
    @ObservedObject var upload: ImageUploadCoordinator
    let context: String
    var disabled = false
    var compact = false
    let onPrepareSelection: () -> Void
    let onUploaded: (URL) -> Void

    @EnvironmentObject private var settings: AppSettings
    @State private var showSettings = false
    @State private var showNotice = false
    @State private var showPicker = false
    @State private var pickedImage: PhotosPickerItem?
    @State private var pickerContext: String?

    var body: some View {
        Button {
            onPrepareSelection()
            if ImgurUploadClient.normalizedClientID(settings.imgurClientID) == nil {
                showSettings = true
            } else {
                showNotice = true
            }
        } label: {
            Group {
                if upload.isUploading {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "photo.badge.plus")
                        .font(.system(size: compact ? 16 : 19))
                }
            }
            .frame(width: compact ? 32 : 40, height: compact ? 32 : 44)
            .foregroundStyle(Theme.accent)
            .background(Theme.inset, in: RoundedRectangle(cornerRadius: compact ? 9 : 14))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled || upload.isUploading)
        .accessibilityLabel(upload.isUploading ? "图片上传中" : "上传图片")
        .confirmationDialog("上传到 Imgur", isPresented: $showNotice, titleVisibility: .visible) {
            Button("选择图片并上传") {
                guard !disabled, !upload.isUploading, upload.context == context else { return }
                onPrepareSelection()
                pickerContext = context
                pickedImage = nil
                showPicker = true
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("选中的图片会发送到第三方 Imgur，获得可公开访问的链接。请勿选择敏感图片。图片会按需缩小并移除原始元数据；取消操作不会删除已上传的图片。")
        }
        .photosPicker(isPresented: $showPicker, selection: $pickedImage, matching: .images)
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                ImgurSettingsView()
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { showSettings = false } } }
            }
        }
        .alert("图片未插入", isPresented: Binding(
            get: { upload.error != nil },
            set: { if !$0 { upload.error = nil } }
        )) {
            Button("好", role: .cancel) { upload.error = nil }
        } message: {
            Text(upload.error ?? "")
        }
        .onAppear { upload.setContext(context) }
        .onChange(of: context) { _, value in
            upload.setContext(value)
            pickerContext = nil
            pickedImage = nil
            showPicker = false
            showNotice = false
        }
        .onChange(of: pickedImage) { _, item in
            guard let item, let expected = pickerContext,
                  expected == context, !disabled else { return }
            pickedImage = nil
            pickerContext = nil
            upload.start(context: expected, clientID: settings.imgurClientID, prepare: {
                guard let selected = try await item.loadTransferable(type: PickedUploadImage.self) else {
                    throw ImgurUploadError.unsupportedImage
                }
                return selected.image
            }, onUploaded: onUploaded)
        }
        .onDisappear {
            pickerContext = nil
            pickedImage = nil
            upload.setContext(nil)
        }
    }
}
