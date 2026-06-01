import Foundation

public struct AnyCodable: Codable, Hashable {
    public let value: Any?

    public init(_ value: Any?) {
        if let value = value as? AnyCodable {
            self.value = value.value
        } else {
            self.value = value
        }
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            value = nil
        } else if let decoded = try? container.decode(Bool.self) {
            value = decoded
        } else if let decoded = try? container.decode(Int.self) {
            value = decoded
        } else if let decoded = try? container.decode(Int64.self) {
            value = decoded
        } else if let decoded = try? container.decode(Double.self) {
            value = decoded
        } else if let decoded = try? container.decode(String.self) {
            value = decoded
        } else if let decoded = try? container.decode([AnyCodable].self) {
            value = decoded.map(\.value)
        } else if let decoded = try? container.decode([String: AnyCodable].self) {
            value = decoded.mapValues(\.value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unsupported JSON value"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch value {
        case nil:
            try container.encodeNil()
        case let value as Bool:
            try container.encode(value)
        case let value as Int:
            try container.encode(value)
        case let value as Int64:
            try container.encode(value)
        case let value as Double:
            try container.encode(value)
        case let value as Float:
            try container.encode(value)
        case let value as String:
            try container.encode(value)
        case let value as [Any?]:
            try container.encode(value.map { AnyCodable($0) })
        case let value as [Any]:
            try container.encode(value.map { AnyCodable($0) })
        case let value as [String: Any?]:
            try container.encode(value.mapValues { AnyCodable($0) })
        case let value as [String: Any]:
            try container.encode(value.mapValues { AnyCodable($0) })
        case let value as AnyCodable:
            try value.encode(to: encoder)
        default:
            throw EncodingError.invalidValue(
                value as Any,
                EncodingError.Context(
                    codingPath: container.codingPath,
                    debugDescription: "Unsupported JSON value"
                )
            )
        }
    }

    public static func == (lhs: AnyCodable, rhs: AnyCodable) -> Bool {
        String(describing: lhs.value) == String(describing: rhs.value)
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(String(describing: value))
    }
}
