import Foundation

struct AppClipCreateSessionRequest: Encodable {
    let linkToken: String
}

struct AppClipSessionResponse: Decodable {
    let sessionToken: String
    let expiresAt: Date
    let account: WebsiteAccountProfile
    let linkedAccounts: WebsiteLinkedAccountsResponse
    let subscription: SubscriptionSummaryResponse
    let appAccountToken: UUID
    let storekitProductIds: [String]

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

struct WebsiteAccountProfile: Decodable {
    let subject: String?
    let accountId: String?
    let email: String?
    let displayName: String?
}

struct WebsiteLinkedAccountsResponse: Decodable {
    let account: WebsiteAccountProfile
    let integrations: [WebsiteIntegrationSummary]
}

struct WebsiteIntegrationSummary: Decodable {
    let modelAccess: WebsiteModelAccessSummary?
}

struct WebsiteModelAccessSummary: Decodable {
    let isPremium: Bool
    let currentModel: String
    let currentModelLabel: String
}

struct SubscriptionSummaryResponse: Decodable {
    let accountId: String
    let isPremium: Bool
    let entitlementSource: String
    let premiumUntil: Date?
    let plans: [SubscriptionPlan]
    let subscriptions: [AdminSubscriptionItem]
}

struct SubscriptionPlan: Decodable {
    let key: String
    let displayName: String
    let description: String?
    let priceAmount: String
    let currency: String
    let billingInterval: String
    let trialDurationDays: Int
    let provider: String
    let active: Bool
}

struct AdminSubscriptionItem: Decodable {
    let subscriptionId: String?
    let provider: String?
    let planKey: String?
    let status: String?
}

struct SubscriptionStoreKitTransactionRequest: Encodable {
    let signedTransactionInfo: String
    let productId: String
    let transactionId: String
}
