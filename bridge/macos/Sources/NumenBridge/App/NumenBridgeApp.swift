import SwiftUI

@main
struct NumenBridgeApp: App {
    init() {
        Task.detached(priority: .userInitiated) {
            do {
                try await BridgeServer.run()
            } catch {
                fputs("Numen Bridge server failed: \(error)\n", stderr)
            }
        }
    }

    var body: some Scene {
        MenuBarExtra("Numen Bridge", systemImage: "waveform") {
            Text("本机语音服务")
            Text("127.0.0.1:\(BridgeServer.port)")
            Divider()
            Button("退出") {
                NSApplication.shared.terminate(nil)
            }
        }
    }
}
