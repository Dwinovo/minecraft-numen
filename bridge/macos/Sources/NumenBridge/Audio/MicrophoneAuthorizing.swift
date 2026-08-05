import AVFoundation

protocol MicrophoneAuthorizing: Sendable {
    func status() -> MicrophonePermission
    func requestAccess() async -> Bool
}

struct AVFoundationMicrophoneAuthorizer: MicrophoneAuthorizing {
    func status() -> MicrophonePermission {
        Self.map(AVCaptureDevice.authorizationStatus(for: .audio))
    }

    func requestAccess() async -> Bool {
        await withCheckedContinuation { continuation in
            AVCaptureDevice.requestAccess(for: .audio) { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    static func map(_ status: AVAuthorizationStatus) -> MicrophonePermission {
        switch status {
        case .notDetermined: .notDetermined
        case .denied: .denied
        case .restricted: .restricted
        case .authorized: .authorized
        @unknown default: .restricted
        }
    }
}
