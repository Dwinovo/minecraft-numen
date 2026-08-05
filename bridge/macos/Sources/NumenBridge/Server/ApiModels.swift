import Hummingbird

enum MicrophonePermission: String, Codable, Sendable {
    case notDetermined = "not_determined"
    case denied
    case restricted
    case authorized
}

struct HealthResponse: ResponseCodable, Equatable, Sendable {
    let version: String
    let apiVersion: Int
    let platform: String
    let permission: MicrophonePermission
    let providerReady: Bool

    enum CodingKeys: String, CodingKey {
        case version
        case apiVersion = "api_version"
        case platform
        case permission
        case providerReady = "provider_ready"
    }
}
