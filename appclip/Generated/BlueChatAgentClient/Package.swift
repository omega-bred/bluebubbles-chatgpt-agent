// swift-tools-version:5.9

import PackageDescription

let package = Package(
    name: "BlueChatAgentClient",
    platforms: [
        .iOS(.v17)
    ],
    products: [
        .library(
            name: "BlueChatAgentClient",
            targets: ["BlueChatAgentClient"]
        ),
        .library(
            name: "AnyCodable",
            targets: ["AnyCodable"]
        )
    ],
    targets: [
        .target(
            name: "AnyCodable",
            path: "Sources/AnyCodable"
        ),
        .target(
            name: "BlueChatAgentClient",
            dependencies: ["AnyCodable"],
            path: "Sources/BlueChatAgentClient"
        )
    ]
)
