import Foundation

enum BridgePreferences {
    static let sttModelKey = "dashscope.stt-model"
    static let ttsModelKey = "dashscope.tts-model"
    static let voiceKey = "dashscope.voice"

    static func sttModel(_ defaults: UserDefaults = .standard) -> String {
        nonEmpty(defaults.string(forKey: sttModelKey), fallback: DashScopeSttClient.defaultModel)
    }

    static func ttsModel(_ defaults: UserDefaults = .standard) -> String {
        nonEmpty(defaults.string(forKey: ttsModelKey), fallback: DashScopeTtsClient.defaultModel)
    }

    static func voice(_ defaults: UserDefaults = .standard) -> String {
        nonEmpty(defaults.string(forKey: voiceKey), fallback: DashScopeTtsClient.defaultVoice)
    }

    private static func nonEmpty(_ value: String?, fallback: String) -> String {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? fallback : trimmed
    }
}

