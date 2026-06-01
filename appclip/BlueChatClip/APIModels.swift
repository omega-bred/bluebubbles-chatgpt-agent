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
    let link: WebsiteAccountLink
    let gcalAccounts: [WebsiteCalendarAccountSummary]
    let linkedAccounts: [WebsiteLinkedIntegrationAccount]
    let modelAccess: WebsiteModelAccessSummary?
}

struct WebsiteAccountLink: Decodable {
    let linkId: String
    let accountId: String
    let identities: [WebsiteAccountIdentity]
    let createdAt: Date?
}

struct WebsiteAccountIdentity: Decodable, Identifiable {
    let type: String
    let identifier: String
    let normalizedIdentifier: String

    var id: String {
        type + ":" + normalizedIdentifier
    }
}

struct WebsiteCalendarAccountSummary: Decodable, Identifiable {
    let accountId: String
    let accountKey: String
    let email: String?

    var id: String {
        accountKey
    }
}

struct WebsiteLinkedIntegrationAccount: Decodable, Identifiable {
    let type: String
    let accountKey: String
    let email: String?
    let label: String
    let unlinkable: Bool

    var id: String {
        type + ":" + accountKey
    }
}

struct WebsiteModelAccessSummary: Decodable {
    let accountId: String?
    let isPremium: Bool
    let currentModel: String
    let currentModelLabel: String
    let modelSelectionAllowed: Bool?
    let modelSelectionConfigurable: Bool?
    let readOnlyReason: String?
    let availableModels: [WebsiteModelOption]?
}

struct WebsiteModelOption: Decodable, Identifiable {
    let model: String
    let label: String
    let provider: String?
    let enabled: Bool

    var id: String {
        model
    }
}

struct WebsiteModelSelectionRequest: Encodable {
    let model: String
}

struct WebsiteModelSelectionResponse: Decodable {
    let modelAccess: WebsiteModelAccessSummary
    let message: String
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
