import Combine
import Foundation

@MainActor
final class BridgeState: ObservableObject {
    enum ServerStatus: Equatable {
        case starting
        case running
        case failed
    }

    @Published private(set) var serverStatus: ServerStatus = .starting
    @Published private(set) var permission: MicrophonePermission
    @Published private(set) var providerReady: Bool
    @Published private(set) var isCapturing = false
    @Published private(set) var lastError: String?

    private let authorizer: any MicrophoneAuthorizing
    private let credentials: ProviderCredentials

    init(
        authorizer: any MicrophoneAuthorizing = AVFoundationMicrophoneAuthorizer(),
        credentials: ProviderCredentials = ProviderCredentials(store: KeychainStore())
    ) {
        self.authorizer = authorizer
        self.credentials = credentials
        self.permission = authorizer.status()
        self.providerReady = (try? credentials.isReady()) == true
    }

    var headline: String {
        if let lastError { return lastError }
        if isCapturing { return "正在录音" }
        if permission == .denied { return "麦克风权限已拒绝" }
        if permission == .restricted { return "麦克风权限受限" }
        if serverStatus != .running { return "本机服务正在启动" }
        if !providerReady { return "请配置 DashScope API Key" }
        return "Numen Bridge 已就绪"
    }

    var iconName: String {
        if lastError != nil || permission == .denied || permission == .restricted {
            return "exclamationmark.triangle.fill"
        }
        if isCapturing { return "waveform.circle.fill" }
        if serverStatus == .running && providerReady && permission == .authorized {
            return "checkmark.circle.fill"
        }
        return "waveform"
    }

    var permissionLabel: String {
        switch permission {
        case .notDetermined: "尚未请求"
        case .denied: "已拒绝"
        case .restricted: "受系统限制"
        case .authorized: "已允许"
        }
    }

    func serverStarted() {
        serverStatus = .running
        lastError = nil
    }

    func updatePermission(_ permission: MicrophonePermission) {
        self.permission = permission
        if permission == .authorized { lastError = nil }
    }

    func updateProviderReady(_ ready: Bool) {
        providerReady = ready
    }

    func captureStarted() {
        isCapturing = true
        lastError = nil
    }

    func captureEnded() {
        isCapturing = false
    }

    func fail(_ message: String) {
        isCapturing = false
        serverStatus = .failed
        lastError = message
    }

    func requestMicrophonePermission() async {
        let granted = await authorizer.requestAccess()
        permission = granted ? .authorized : authorizer.status()
    }

    func save(apiKey: String) throws {
        try credentials.save(apiKey: apiKey)
        providerReady = try credentials.isReady()
    }
}

