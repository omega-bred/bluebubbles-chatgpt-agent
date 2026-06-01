import Foundation

struct BlueChatAPIClient {
    private let baseURL = URL(string: "https://bluechat.bre.land")!
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    init() {
        decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .custom { decoder in
            try Self.decodeDate(decoder)
        }

        encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
    }

    func createSession(linkToken: String) async throws -> AppClipSessionResponse {
        var request = URLRequest(url: baseURL.appending(path: "/api/v1/appClip/createSession.appClipSessions"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(AppClipCreateSessionRequest(linkToken: linkToken))
        return try await send(request)
    }

    func getSession(sessionToken: String) async throws -> AppClipSessionResponse {
        var request = URLRequest(url: baseURL.appending(path: "/api/v1/appClip/get.appClipSession"))
        request.setValue(sessionToken, forHTTPHeaderField: "X-App-Clip-Session")
        return try await send(request)
    }

    func validateStoreKitTransaction(
        sessionToken: String,
        signedTransactionInfo: String,
        productId: String,
        transactionId: String
    ) async throws -> SubscriptionSummaryResponse {
        var request = URLRequest(
            url: baseURL.appending(path: "/api/v1/subscription/validateStoreKit.subscriptionProviderEvents")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(sessionToken, forHTTPHeaderField: "X-App-Clip-Session")
        request.httpBody = try encoder.encode(
            SubscriptionStoreKitTransactionRequest(
                signedTransactionInfo: signedTransactionInfo,
                productId: productId,
                transactionId: transactionId
            )
        )
        return try await send(request)
    }

    func updatePreferredModel(
        sessionToken: String,
        model: String
    ) async throws -> WebsiteModelSelectionResponse {
        var request = URLRequest(
            url: baseURL.appending(path: "/api/v1/websiteAccount/updateModel.websiteAccountModels")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(sessionToken, forHTTPHeaderField: "X-App-Clip-Session")
        request.httpBody = try encoder.encode(WebsiteModelSelectionRequest(model: model))
        return try await send(request)
    }

    func trackEvent(
        sessionToken: String,
        eventName: String,
        properties: [String: String] = [:]
    ) async throws -> AppClipEventResponse {
        var request = URLRequest(url: baseURL.appending(path: "/api/v1/appClip/createEvent.appClipEvents"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(sessionToken, forHTTPHeaderField: "X-App-Clip-Session")
        request.httpBody = try encoder.encode(AppClipEventRequest(eventName: eventName, properties: properties))
        return try await send(request)
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, 200..<300 ~= http.statusCode else {
            throw APIError.requestFailed
        }
        return try decoder.decode(T.self, from: data)
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

    enum APIError: LocalizedError {
        case requestFailed

        var errorDescription: String? {
            "BlueChatAI request failed."
        }
    }
}
