import AnyCodable
import BlueChatAgentClient
import Foundation

@MainActor
enum GeneratedAPIConfiguration {
    private static let basePath = "https://bluechat.bre.land"
    private static let sessionHeader = "X-App-Clip-Session"
    private static let sessionTokenKey = "bluechat.appclip.session-token"
    private static var didConfigureCoders = false
    private static var pendingSessionRequest: Task<Void, Never>?
    private(set) static var sessionID = UUID()

    static var hasSession: Bool {
        UserDefaults.standard.string(forKey: sessionTokenKey) != nil
    }

    enum SessionError: LocalizedError {
        case missing

        var errorDescription: String? {
            "Ask BlueChatAI to send a fresh link to continue."
        }
    }

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

    static func createSession(
        linkToken: String
    ) async throws -> (response: AppClipSessionResponse, id: UUID) {
        try await enqueue {
            configure()
            let session = try await AppClipAPI.appClipCreateSession(
                appClipCreateSessionRequest: AppClipCreateSessionRequest(linkToken: linkToken)
            )
            UserDefaults.standard.set(session.sessionToken, forKey: sessionTokenKey)
            sessionID = UUID()
            return (session, sessionID)
        }
    }

    static func executeWithSession<T>(
        for expectedSessionID: UUID,
        requestBuilder: @escaping @MainActor () -> RequestBuilder<T>
    ) async throws -> T {
        try await enqueue {
            guard expectedSessionID == sessionID,
                  let token = UserDefaults.standard.string(forKey: sessionTokenKey) else {
                throw SessionError.missing
            }
            configure()
            do {
                let response = try await requestBuilder().addHeader(name: sessionHeader, value: token).execute()
                saveReplacement(response.header.first {
                    $0.key.caseInsensitiveCompare(sessionHeader) == .orderedSame
                }?.value)
                return response.body
            } catch {
                if case let ErrorResponse.error(status, _, response, _) = error {
                    let replacement = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: sessionHeader)
                    if status == 401 && replacement == nil {
                        UserDefaults.standard.removeObject(forKey: sessionTokenKey)
                        throw SessionError.missing
                    }
                    saveReplacement(replacement)
                }
                throw error
            }
        }
    }

    private static func enqueue<T>(_ operation: @escaping @MainActor () async throws -> T) async throws -> T {
        let previous = pendingSessionRequest
        let request = Task {
            await previous?.value
            return try await operation()
        }
        pendingSessionRequest = Task { _ = await request.result }
        return try await request.value
    }

    private static func saveReplacement(_ token: String?) {
        if let token, !token.isEmpty {
            UserDefaults.standard.set(token, forKey: sessionTokenKey)
        }
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
        replacing(subscription: subscription, conversationSettings: conversationSettings)
    }

    func replacing(conversationSettings: ConversationSettingsResponse) -> AppClipSessionResponse {
        replacing(subscription: subscription, conversationSettings: conversationSettings)
    }

    private func replacing(
        subscription: SubscriptionSummaryResponse,
        conversationSettings: ConversationSettingsResponse?
    ) -> AppClipSessionResponse {
        AppClipSessionResponse(
            sessionToken: sessionToken,
            purpose: purpose,
            expiresAt: expiresAt,
            account: account,
            linkedAccounts: linkedAccounts,
            subscription: subscription,
            conversationSettings: conversationSettings
        )
    }
}
