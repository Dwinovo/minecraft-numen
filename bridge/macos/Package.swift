// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "NumenBridge",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "NumenBridge", targets: ["NumenBridge"])
    ],
    dependencies: [
        .package(url: "https://github.com/hummingbird-project/hummingbird.git", exact: "2.26.0"),
        .package(url: "https://github.com/hummingbird-project/hummingbird-websocket.git", exact: "2.7.0"),
        .package(url: "https://github.com/swiftlang/swift-testing.git", revision: "swift-6.3.2-RELEASE")
    ],
    targets: [
        .executableTarget(
            name: "NumenBridge",
            dependencies: [
                .product(name: "Hummingbird", package: "hummingbird"),
                .product(name: "HummingbirdWebSocket", package: "hummingbird-websocket")
            ]
        ),
        .testTarget(
            name: "NumenBridgeTests",
            dependencies: [
                "NumenBridge",
                .product(name: "Testing", package: "swift-testing")
            ],
            linkerSettings: [
                .unsafeFlags([
                    "-L", "/Library/Developer/CommandLineTools/Library/Developer/usr/lib",
                    "-Xlinker", "-rpath",
                    "-Xlinker", "/Library/Developer/CommandLineTools/Library/Developer/usr/lib"
                ])
            ]
        )
    ]
)
