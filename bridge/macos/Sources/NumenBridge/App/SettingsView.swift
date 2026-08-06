import SwiftUI

struct SettingsView: View {
    @ObservedObject var state: BridgeState
    @AppStorage(BridgePreferences.providerKey) private var providerRaw = ProviderKind.dashscope.rawValue
    @AppStorage(BridgePreferences.sttModelKey) private var sttModel = DashScopeSttClient.defaultModel
    @AppStorage(BridgePreferences.ttsModelKey) private var ttsModel = DashScopeTtsClient.defaultModel
    @AppStorage(BridgePreferences.voiceKey) private var voice = DashScopeTtsClient.defaultVoice
    @AppStorage(BridgePreferences.customBaseURLKey) private var customBaseURL = ""
    @AppStorage(BridgePreferences.customSttModelKey) private var customSttModel = ""
    @AppStorage(BridgePreferences.customTtsModelKey) private var customTtsModel = ""
    @AppStorage(BridgePreferences.customVoiceKey) private var customVoice = ""
    @State private var apiKey = ""
    @State private var customApiKey = ""
    @State private var resultMessage = ""

    private var provider: ProviderKind {
        ProviderKind(rawValue: providerRaw) ?? .dashscope
    }

    var body: some View {
        Form {
            Section("服务商") {
                Picker("Provider", selection: $providerRaw) {
                    Text("DashScope（阿里云百炼）").tag(ProviderKind.dashscope.rawValue)
                    Text("Custom（OpenAI 兼容）").tag(ProviderKind.custom.rawValue)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }

            if provider == .dashscope {
                dashScopeSection
                Section("DashScope 模型") {
                    TextField("语音识别模型", text: $sttModel)
                    TextField("语音合成模型", text: $ttsModel)
                    TextField("音色", text: $voice)
                }
            } else {
                customSection
            }
        }
        .formStyle(.grouped)
        .frame(width: 540, height: provider == .custom ? 460 : 330)
        .padding(12)
        .onChange(of: providerRaw) { _, _ in
            Task { await state.refreshAfterSettingsChange() }
        }
        .onChange(of: customBaseURL) { _, _ in
            Task { await state.refreshAfterSettingsChange() }
        }
    }

    private var dashScopeSection: some View {
        Section("DashScope") {
            LabeledContent("区域", value: "中国大陆")
            SecureField("API Key", text: $apiKey)
            keyButtons(save: saveKey, remove: removeKey)
        }
    }

    @ViewBuilder
    private var customSection: some View {
        Section("Custom（任意 OpenAI 兼容端点）") {
            TextField("Base URL", text: $customBaseURL, prompt: Text("https://api.example.com/v1"))
                .autocorrectionDisabled()
            SecureField("API Key", text: $customApiKey)
            keyButtons(save: saveCustomKey, remove: removeCustomKey)
        }
        Section("Custom 模型") {
            TextField("语音识别模型", text: $customSttModel, prompt: Text("例如 whisper-1"))
                .autocorrectionDisabled()
            TextField("语音合成模型", text: $customTtsModel, prompt: Text("例如 tts-1"))
                .autocorrectionDisabled()
            TextField("音色", text: $customVoice, prompt: Text("例如 alloy"))
                .autocorrectionDisabled()
        }
    }

    private func keyButtons(save: @escaping () -> Void, remove: @escaping () -> Void) -> some View {
        HStack {
            Button {
                save()
            } label: {
                Label("保存", systemImage: "key.fill")
            }
            .disabled(currentDraftKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

            Button(role: .destructive) {
                remove()
            } label: {
                Label("删除", systemImage: "trash")
            }
            Spacer()
            Text(resultMessage)
                .foregroundStyle(.secondary)
        }
    }

    private var currentDraftKey: String {
        provider == .custom ? customApiKey : apiKey
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

    private func saveCustomKey() {
        do {
            try state.save(customApiKey: customApiKey)
            customApiKey = ""
            resultMessage = "已保存到钥匙串"
        } catch {
            resultMessage = "保存失败：\(error)"
        }
    }

    private func removeCustomKey() {
        do {
            try state.save(customApiKey: "")
            customApiKey = ""
            resultMessage = "已从钥匙串删除"
        } catch {
            resultMessage = "删除失败：\(error)"
        }
    }
}
