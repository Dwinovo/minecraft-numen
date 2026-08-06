import Foundation

enum ProviderKind: String, Sendable {
    case dashscope
    case custom
}

enum BridgePreferences {
    static let providerKey = "bridge.provider"
    static let sttModelKey = "dashscope.stt-model"
    static let ttsModelKey = "dashscope.tts-model"
    static let voiceKey = "dashscope.voice"
    static let customBaseURLKey = "custom.base-url"
    static let customSttModelKey = "custom.stt-model"
    static let customTtsModelKey = "custom.tts-model"
    static let customVoiceKey = "custom.voice"

    static func provider(_ defaults: UserDefaults = .standard) -> ProviderKind {
        ProviderKind(rawValue: nonEmpty(defaults.string(forKey: providerKey), fallback: "")) ?? .dashscope
    }

    static func customBaseURL(_ defaults: UserDefaults = .standard) -> String {
        nonEmpty(defaults.string(forKey: customBaseURLKey), fallback: "")
    }

    static func customSttModel(_ defaults: UserDefaults = .standard) -> String {
        nonEmpty(defaults.string(forKey: customSttModelKey), fallback: "")
    }

    static func customTtsModel(_ defaults: UserDefaults = .standard) -> String {
        nonEmpty(defaults.string(forKey: customTtsModelKey), fallback: "")
    }

    static func customVoice(_ defaults: UserDefaults = .standard) -> String {
        nonEmpty(defaults.string(forKey: customVoiceKey), fallback: "")
    }

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

