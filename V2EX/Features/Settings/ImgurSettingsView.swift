import SwiftUI

struct ImgurSettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var clientID = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                GroupHeader(title: "Imgur Client ID")
                CardSection(padding: 16) {
                    TextField("填写 Client ID", text: $clientID)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                        .privacySensitive()
                }
                Text("在 Imgur 注册应用后填写 Client ID，不要填写 Client Secret。配置保存在本机，仅用于向 Imgur 上传你主动选择的图片。")
                    .font(.footnote)
                    .foregroundStyle(Theme.muted)
                if !clientID.isEmpty && ImgurUploadClient.normalizedClientID(clientID) == nil {
                    Text("Client ID 只能包含英文字母和数字。")
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
                Text("图片会上传至第三方 Imgur，获得可公开访问的原图链接。请勿上传敏感内容。取消请求、删除草稿或清除此配置不会删除已经上传的图片。")
                    .font(.footnote)
                    .foregroundStyle(Theme.muted)
                Link("注册 Imgur 应用", destination: URL(string: "https://api.imgur.com/oauth2/addclient")!)
                Link("Imgur 隐私政策", destination: URL(string: "https://imgur.com/privacy")!)
                HStack {
                    Button("保存") {
                        guard let value = ImgurUploadClient.normalizedClientID(clientID) else { return }
                        settings.imgurClientID = value
                        dismiss()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(ImgurUploadClient.normalizedClientID(clientID) == nil)
                    Button("清除配置", role: .destructive) {
                        settings.imgurClientID = ""
                        clientID = ""
                    }
                    .disabled(settings.imgurClientID.isEmpty && clientID.isEmpty)
                }
            }
            .padding(Theme.Metric.screenPadding)
        }
        .background(Theme.canvas)
        .navigationTitle("图片上传")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { clientID = settings.imgurClientID }
    }
}
