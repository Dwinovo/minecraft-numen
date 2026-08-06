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

    /// 菜单栏图标：正常状态用自带的麦克风波形模板图（自动适配深浅色），
    /// 出错或录音中时用 SF Symbol 表达状态。
    @ViewBuilder
    private var menuBarIcon: some View {
        if state.isCapturing {
            Image(systemName: "waveform.circle.fill")
        } else if state.lastError != nil || state.permission == .denied || state.permission == .restricted {
            Image(systemName: "exclamationmark.triangle.fill")
        } else if let image = Self.menuBarTemplateIcon {
            Image(nsImage: image)
        } else {
            Image(systemName: state.iconName)
        }
    }

    private static let menuBarTemplateIcon: NSImage? = {
        guard let image = NSImage(named: "MenuBarIcon") else { return nil }
        image.isTemplate = true
        return image
    }()

    var body: some Scene {
        WindowGroup("Numen Bridge", id: "main") {
            BridgeDashboardView(state: state)
        }
        .defaultSize(width: 520, height: 300)
        .windowResizability(.contentSize)

        MenuBarExtra {
            BridgeMenuView(state: state)
        } label: {
            menuBarIcon
        }
        Settings {
            SettingsView(state: state)
        }
    }
}
