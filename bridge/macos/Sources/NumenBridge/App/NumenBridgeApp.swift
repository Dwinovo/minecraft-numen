import AppKit
import SwiftUI

final class NumenBridgeAppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }
}

@main
struct NumenBridgeApp: App {
    @NSApplicationDelegateAdaptor(NumenBridgeAppDelegate.self) private var appDelegate
    @StateObject private var state: BridgeState

    init() {
        let state = BridgeState()
        _state = StateObject(wrappedValue: state)
        // 先启动本机服务，再异步刷新凭据状态，避免钥匙串授权弹窗阻塞启动。
        Task.detached(priority: .userInitiated) {
            do {
                try await BridgeServer.run(state: state)
            } catch {
                await state.fail("本机服务启动失败：\(error)")
            }
        }
        Task { @MainActor in
            await state.refreshProviderReady()
        }
        // 首次启动就触发系统授权，避免 Java 已开始等待 WebSocket 后才出现弹窗。
        Task { @MainActor in
            await Task.yield()
            await state.requestMicrophonePermission()
        }
    }

    var body: some Scene {
        WindowGroup("Numen Bridge", id: "main") {
            BridgeDashboardView(state: state)
        }
        .defaultSize(width: 520, height: 300)
        .windowResizability(.contentSize)

        MenuBarExtra("Numen Bridge", systemImage: state.iconName) {
            BridgeMenuView(state: state)
        }
        Settings {
            SettingsView(state: state)
        }
    }
}
