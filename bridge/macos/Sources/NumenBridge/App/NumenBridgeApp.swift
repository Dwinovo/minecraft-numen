import SwiftUI

@main
struct NumenBridgeApp: App {
    @StateObject private var state: BridgeState

    init() {
        let state = BridgeState()
        _state = StateObject(wrappedValue: state)
        Task.detached(priority: .userInitiated) {
            do {
                try await BridgeServer.run(state: state)
            } catch {
                await state.fail("本机服务启动失败：\(error)")
            }
        }
        // 首次启动就触发系统授权，避免 Java 已开始等待 WebSocket 后才出现弹窗。
        Task { @MainActor in
            await Task.yield()
            await state.requestMicrophonePermission()
        }
    }

    var body: some Scene {
        MenuBarExtra("Numen Bridge", systemImage: state.iconName) {
            Label(state.headline, systemImage: state.iconName)
            Text("麦克风：\(state.permissionLabel)")
            Text(state.providerReady ? "DashScope：已配置" : "DashScope：未配置")
            Divider()
            Button {
                Task { await state.requestMicrophonePermission() }
            } label: {
                Label("请求麦克风权限", systemImage: "mic.fill")
            }
            SettingsLink {
                Label("设置", systemImage: "gearshape")
            }
            Divider()
            Button("退出") {
                NSApplication.shared.terminate(nil)
            }
        }
        Settings {
            SettingsView(state: state)
        }
    }
}
