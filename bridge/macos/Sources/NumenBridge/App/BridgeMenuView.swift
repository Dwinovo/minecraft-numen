import AppKit
import SwiftUI

struct BridgeMenuView: View {
    @Environment(\.openWindow) private var openWindow
    @ObservedObject var state: BridgeState

    var body: some View {
        Label(state.headline, systemImage: state.iconName)
        Text("麦克风：\(state.permissionLabel)")
        Text(state.providerReady ? "DashScope：已配置" : "DashScope：未配置")
        Divider()
        Button {
            openWindow(id: "main")
            NSApp.activate(ignoringOtherApps: true)
        } label: {
            Label("打开状态窗口", systemImage: "macwindow")
        }
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
}
