import AnyCodable
import BlueChatAgentClient
import Foundation

enum GeneratedAPIConfiguration {
    private static let basePath = "https://bluechat.bre.land"
    private static let sessionHeader = "X-App-Clip-Session"
    private static var didConfigureCoders = false

    static func configure() {
        BlueChatAgentClientAPI.basePath = basePath
        BlueChatAgentClientAPI.customHeaders = [:]

        guard !didConfigureCoders else {
            return
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            try decodeDate(decoder)
        }
        CodableHelper.jsonDecoder = decoder

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        CodableHelper.jsonEncoder = encoder

        didConfigureCoders = true
    }

    static func executeWithSession<T>(
        _ sessionToken: String,
        requestBuilder: () -> RequestBuilder<T>
    ) async throws -> T {
        configure()
        return try await requestBuilder()
            .addHeader(name: sessionHeader, value: sessionToken)
            .execute()
            .body
    }

    static func appClipEventRequest(
        eventName: String,
        properties: [String: String]
    ) -> AppClipEventRequest {
        let mappedProperties = properties.isEmpty
            ? nil
            : properties.mapValues { AnyCodable($0) }
        return AppClipEventRequest(eventName: eventName, properties: mappedProperties)
    }

    private static func decodeDate(_ decoder: Decoder) throws -> Date {
        let value = try decoder.singleValueContainer().decode(String.self)
        let iso = ISO8601DateFormatter()

        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = iso.date(from: value) {
            return date
        }

        iso.formatOptions = [.withInternetDateTime]
        if let date = iso.date(from: value) {
            return date
        }

        throw DecodingError.dataCorrupted(
            .init(codingPath: decoder.codingPath, debugDescription: "Invalid date")
        )
    }
}

extension AppClipSessionResponse {
    func replacing(subscription: SubscriptionSummaryResponse) -> AppClipSessionResponse {
        AppClipSessionResponse(
            sessionToken: sessionToken,
            expiresAt: expiresAt,
            account: account,
            linkedAccounts: linkedAccounts,
            subscription: subscription,
            appAccountToken: appAccountToken,
            storekitProductIds: storekitProductIds
        )
    }
}
