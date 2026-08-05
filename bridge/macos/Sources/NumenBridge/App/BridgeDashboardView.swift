import SwiftUI

struct BridgeDashboardView: View {
    @ObservedObject var state: BridgeState

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: state.iconName)
                    .font(.system(size: 30))
                    .foregroundStyle(headlineColor)
                    .frame(width: 38, height: 38)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Numen Bridge")
                        .font(.title2.weight(.semibold))
                    Text(state.headline)
                        .foregroundStyle(.secondary)
                }
            }

            Divider()

            Grid(alignment: .leading, horizontalSpacing: 24, verticalSpacing: 12) {
                statusRow("server.rack", "本机服务", serverLabel)
                statusRow("mic.fill", "麦克风", state.permissionLabel)
                statusRow("key.fill", "DashScope", state.providerReady ? "已配置" : "未配置")
                statusRow("waveform", "录音", state.isCapturing ? "正在录音" : "待机")
            }

            Spacer(minLength: 0)
            Divider()

            HStack(spacing: 10) {
                Button {
                    Task { await state.requestMicrophonePermission() }
                } label: {
                    Label("请求麦克风权限", systemImage: "mic.fill")
                }
                .disabled(state.permission == .authorized)

                SettingsLink {
                    Label("设置", systemImage: "gearshape")
                }

                Spacer()
                Text("127.0.0.1:38471")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(24)
        .frame(width: 520, height: 300)
    }

    private var serverLabel: String {
        switch state.serverStatus {
        case .starting: "正在启动"
        case .running: "正在运行"
        case .failed: "启动失败"
        }
    }

    private var headlineColor: Color {
        if state.lastError != nil || state.permission == .denied || state.permission == .restricted {
            return .orange
        }
        if state.serverStatus == .running && state.permission == .authorized {
            return .green
        }
        return .accentColor
    }

    @ViewBuilder
    private func statusRow(_ icon: String, _ label: String, _ value: String) -> some View {
        GridRow {
            Label(label, systemImage: icon)
                .frame(width: 120, alignment: .leading)
            Text(value)
                .foregroundStyle(.secondary)
                .frame(width: 180, alignment: .leading)
        }
    }
}
