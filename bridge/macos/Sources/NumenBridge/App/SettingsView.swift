import SwiftUI

struct SettingsView: View {
    @ObservedObject var state: BridgeState
    @AppStorage(BridgePreferences.sttModelKey) private var sttModel = DashScopeSttClient.defaultModel
    @AppStorage(BridgePreferences.ttsModelKey) private var ttsModel = DashScopeTtsClient.defaultModel
    @AppStorage(BridgePreferences.voiceKey) private var voice = DashScopeTtsClient.defaultVoice
    @State private var apiKey = ""
    @State private var resultMessage = ""

    var body: some View {
        Form {
            Section("DashScope") {
                LabeledContent("区域", value: "中国大陆")
                SecureField("API Key", text: $apiKey)
                HStack {
                    Button {
                        saveKey()
                    } label: {
                        Label("保存", systemImage: "key.fill")
                    }
                    .disabled(apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                    Button(role: .destructive) {
                        removeKey()
                    } label: {
                        Label("删除", systemImage: "trash")
                    }
                    Spacer()
                    Text(resultMessage)
                        .foregroundStyle(.secondary)
                }
            }

            Section("模型") {
                TextField("语音识别模型", text: $sttModel)
                TextField("语音合成模型", text: $ttsModel)
                TextField("音色", text: $voice)
            }
        }
        .formStyle(.grouped)
        .frame(width: 520, height: 330)
        .padding(12)
    }

    private func saveKey() {
        do {
            try state.save(apiKey: apiKey)
            apiKey = ""
            resultMessage = "已保存到钥匙串"
        } catch {
            resultMessage = "保存失败：\(error)"
        }
    }

    private func removeKey() {
        do {
            try state.save(apiKey: "")
            apiKey = ""
            resultMessage = "已从钥匙串删除"
        } catch {
            resultMessage = "删除失败：\(error)"
        }
    }
}

