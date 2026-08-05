import Testing
@testable import NumenBridge

@Suite("Menu bar state")
struct BridgeStateTests {
    @MainActor
    @Test("shows actionable service, permission, provider, capture, and error states")
    func displaysPriorityStates() {
        let state = BridgeState(
            authorizer: StateAuthorizer(permission: .notDetermined),
            credentials: ProviderCredentials(store: StateCredentialStore())
        )
        #expect(state.headline == "本机服务正在启动")

        state.serverStarted()
        #expect(state.headline == "请配置 DashScope API Key")

        state.updatePermission(.denied)
        #expect(state.headline == "麦克风权限已拒绝")

        state.updatePermission(.authorized)
        state.updateProviderReady(true)
        state.captureStarted()
        #expect(state.headline == "正在录音")

        state.fail("端口已被占用")
        #expect(state.headline == "端口已被占用")
        #expect(state.iconName == "exclamationmark.triangle.fill")
    }
}

private struct StateAuthorizer: MicrophoneAuthorizing {
    let permission: MicrophonePermission
    func status() -> MicrophonePermission { permission }
    func requestAccess() async -> Bool { permission == .authorized }
}

private struct StateCredentialStore: CredentialStore {
    func load(account: String) throws -> String? { nil }
    func save(_ value: String, account: String) throws {}
    func delete(account: String) throws {}
}
