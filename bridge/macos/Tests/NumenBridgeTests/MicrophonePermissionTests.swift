import AVFoundation
import Testing
@testable import NumenBridge

@Suite("macOS microphone permission")
struct MicrophonePermissionTests {
    @Test("maps every AVFoundation authorization state")
    func mapsAuthorizationStates() {
        #expect(AVFoundationMicrophoneAuthorizer.map(.notDetermined) == .notDetermined)
        #expect(AVFoundationMicrophoneAuthorizer.map(.denied) == .denied)
        #expect(AVFoundationMicrophoneAuthorizer.map(.restricted) == .restricted)
        #expect(AVFoundationMicrophoneAuthorizer.map(.authorized) == .authorized)
    }
}
