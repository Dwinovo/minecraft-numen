import Foundation
import Security

struct DiscoveryDocument: Codable, Equatable, Sendable {
    let apiVersion: Int
    let baseURL: String
    let token: String

    enum CodingKeys: String, CodingKey {
        case apiVersion = "api_version"
        case baseURL = "base_url"
        case token
    }
}

struct DiscoveryFile: Sendable {
    static let defaultURL = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support/Numen Bridge", isDirectory: true)
        .appendingPathComponent("bridge.json")

    let url: URL
    private let randomBytes: @Sendable () throws -> Data

    init(
        url: URL = defaultURL,
        randomBytes: @escaping @Sendable () throws -> Data = DiscoveryFile.secureRandomBytes
    ) {
        self.url = url
        self.randomBytes = randomBytes
    }

    func loadOrCreate() throws -> DiscoveryDocument {
        if FileManager.default.fileExists(atPath: url.path) {
            return try JSONDecoder().decode(DiscoveryDocument.self, from: Data(contentsOf: url))
        }

        let tokenBytes = try randomBytes()
        guard tokenBytes.count == 32 else {
            throw DiscoveryError.invalidRandomByteCount(tokenBytes.count)
        }
        let document = DiscoveryDocument(
            apiVersion: 1,
            baseURL: "http://127.0.0.1:38471",
            token: tokenBytes.base64URLEncodedString()
        )
        let directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        let data = try JSONEncoder().encode(document)
        guard FileManager.default.createFile(
            atPath: url.path,
            contents: data,
            attributes: [.posixPermissions: 0o600]
        ) else {
            if FileManager.default.fileExists(atPath: url.path) {
                return try JSONDecoder().decode(DiscoveryDocument.self, from: Data(contentsOf: url))
            }
            throw DiscoveryError.cannotCreate(url)
        }
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        return document
    }

    private static func secureRandomBytes() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else {
            throw DiscoveryError.randomFailure(status)
        }
        return Data(bytes)
    }
}

enum DiscoveryError: Error, Equatable {
    case invalidRandomByteCount(Int)
    case randomFailure(OSStatus)
    case cannotCreate(URL)
}

extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
