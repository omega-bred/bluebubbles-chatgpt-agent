import StoreKit
import SwiftUI

struct ClipRootView: View {
    @ObservedObject var model: ClipViewModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    statusPanel
                    billingPanel
                    accountPanel
                    linkedServicesPanel
                    linkedAddressesPanel
                }
                .padding(20)
            }
            .navigationTitle("BlueChatAI")
            .task {
                await model.restoreSessionIfPossible()
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("BlueChatAI", systemImage: "message.fill")
                .font(.title.weight(.semibold))
            Text(model.accountEmailOrId)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var statusPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(model.title)
                .font(.headline)
            Text(model.subtitle)
                .font(.body)
                .foregroundStyle(.secondary)
            if model.isLoading {
                ProgressView()
            }
            if let error = model.errorMessage {
                Text(error)
                    .font(.callout)
                    .foregroundStyle(.red)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private var billingPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Billing")
                .font(.headline)
            Text(model.billingSummary)
                .foregroundStyle(.secondary)
            if let error = model.billingErrorMessage {
                Text(error)
                    .font(.callout)
                    .foregroundStyle(.red)
            }
            Button {
                Task {
                    await model.purchasePremium()
                }
            } label: {
                HStack {
                    if model.purchaseInProgress {
                        ProgressView()
                    }
                    Text(model.purchaseButtonTitle)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!model.canPurchase)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private var accountPanel: some View {
        if model.session != nil {
            VStack(alignment: .leading, spacing: 12) {
                Text("Website Account")
                    .font(.headline)
                InfoRow(label: "Email", value: model.accountEmailOrId)
                InfoRow(label: "Account ID", value: model.accountId)
                if let modelLabel = model.modelLabel {
                    InfoRow(label: "Model", value: modelLabel)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(.thinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    @ViewBuilder
    private var linkedServicesPanel: some View {
        if model.session != nil {
            VStack(alignment: .leading, spacing: 12) {
                Text("Linked Services")
                    .font(.headline)
                if model.linkedIntegrationAccounts.isEmpty {
                    Text("No OAuth integrations linked.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.linkedIntegrationAccounts) { account in
                        InfoRow(
                            label: model.integrationLabel(account),
                            value: model.integrationIdentifier(account)
                        )
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(.thinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    @ViewBuilder
    private var linkedAddressesPanel: some View {
        if model.session != nil {
            VStack(alignment: .leading, spacing: 12) {
                Text("Linked Chat Addresses")
                    .font(.headline)
                if model.linkedIdentities.isEmpty {
                    Text("No chat addresses linked yet.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.linkedIdentities) { identity in
                        InfoRow(label: model.identityLabel(identity), value: identity.identifier)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(.thinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
}

private struct InfoRow: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.callout)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

@MainActor
final class ClipViewModel: ObservableObject {
    @Published private(set) var session: AppClipSessionResponse?
    @Published private(set) var product: Product?
    @Published var isLoading = false
    @Published var purchaseInProgress = false
    @Published var errorMessage: String?
    @Published var billingErrorMessage: String?

    private let client = BlueChatAPIClient()
    private let storeKit = StoreKitManager()
    private let sessionTokenKey = "bluechat.appclip.session-token"

    var title: String {
        if session == nil {
            return "Waiting for link"
        }
        return session?.subscription.isPremium == true ? "Premium is active" : "Free access"
    }

    var subtitle: String {
        if session == nil {
            return "Open your BlueChatAI link from Messages."
        }
        return "Your account is ready."
    }

    var accountEmailOrId: String {
        session?.account.email ?? session?.account.accountId ?? "App Clip"
    }

    var accountId: String {
        session?.account.accountId ?? "Unknown"
    }

    var modelLabel: String? {
        guard let modelAccess else {
            return nil
        }
        return modelAccess.currentModelLabel
    }

    var linkedIdentities: [WebsiteAccountIdentity] {
        dedupe(session?.linkedAccounts.integrations.flatMap { $0.link.identities } ?? [], by: \.id)
    }

    var linkedIntegrationAccounts: [WebsiteLinkedIntegrationAccount] {
        dedupe(session?.linkedAccounts.integrations.flatMap(\.linkedAccounts) ?? [], by: \.id)
    }

    private var modelAccess: WebsiteModelAccessSummary? {
        session?.linkedAccounts.integrations.compactMap(\.modelAccess).first
    }

    var billingSummary: String {
        if session?.subscription.isPremium == true {
            return "Premium model access is enabled."
        }
        if let product {
            return product.displayPrice + " per month"
        }
        return "Premium is available through Apple."
    }

    var purchaseButtonTitle: String {
        if purchaseInProgress {
            return "Purchasing"
        }
        return session?.subscription.isPremium == true ? "Premium active" : "Upgrade"
    }

    var canPurchase: Bool {
        session != nil && product != nil && !purchaseInProgress && session?.subscription.isPremium != true
    }

    func handleInvocation(_ url: URL) {
        guard let token = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first(where: { $0.name == "token" })?
            .value
        else {
            errorMessage = "Missing link token."
            return
        }
        Task {
            await createSession(linkToken: token)
        }
    }

    func restoreSessionIfPossible() async {
        guard session == nil, let token = UserDefaults.standard.string(forKey: sessionTokenKey) else {
            return
        }
        await loadSession(sessionToken: token)
    }

    func purchasePremium() async {
        guard let session, let product else {
            return
        }
        purchaseInProgress = true
        billingErrorMessage = nil
        defer {
            purchaseInProgress = false
        }
        do {
            let purchase = try await storeKit.purchase(product: product, appAccountToken: session.appAccountToken)
            let updated = try await client.validateStoreKitTransaction(
                sessionToken: session.sessionToken,
                signedTransactionInfo: purchase.jwsRepresentation,
                productId: product.id,
                transactionId: String(purchase.transaction.id)
            )
            await purchase.transaction.finish()
            self.session = session.replacing(subscription: updated)
        } catch {
            billingErrorMessage = error.localizedDescription
        }
    }

    func identityLabel(_ identity: WebsiteAccountIdentity) -> String {
        switch identity.type {
        case "imessage_email":
            return "BlueChat email"
        case "imessage_phone":
            return "BlueChat phone"
        case "lxmf_address":
            return "LXMF address"
        default:
            return "Chat address"
        }
    }

    func integrationLabel(_ account: WebsiteLinkedIntegrationAccount) -> String {
        switch account.type {
        case "gcal":
            return "Google Calendar"
        default:
            return account.label
        }
    }

    func integrationIdentifier(_ account: WebsiteLinkedIntegrationAccount) -> String {
        account.email ?? account.accountKey
    }

    private func createSession(linkToken: String) async {
        isLoading = true
        errorMessage = nil
        billingErrorMessage = nil
        defer {
            isLoading = false
        }
        do {
            let session = try await client.createSession(linkToken: linkToken)
            UserDefaults.standard.set(session.sessionToken, forKey: sessionTokenKey)
            self.session = session
            await loadProductIfNeeded(for: session)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func loadSession(sessionToken: String) async {
        isLoading = true
        errorMessage = nil
        billingErrorMessage = nil
        defer {
            isLoading = false
        }
        do {
            let session = try await client.getSession(sessionToken: sessionToken)
            self.session = session
            await loadProductIfNeeded(for: session)
        } catch {
            UserDefaults.standard.removeObject(forKey: sessionTokenKey)
            errorMessage = error.localizedDescription
        }
    }

    private func loadProductIfNeeded(for session: AppClipSessionResponse) async {
        guard !session.subscription.isPremium else {
            product = nil
            return
        }
        await loadProduct(productIds: session.storekitProductIds)
    }

    private func loadProduct(productIds: [String]) async {
        guard !productIds.isEmpty else {
            product = nil
            billingErrorMessage = "Premium purchases are not configured yet."
            return
        }
        do {
            product = try await storeKit.loadProducts(productIds: productIds).first
            if product == nil {
                billingErrorMessage = "Premium purchases are not available in App Store Connect yet."
            }
        } catch {
            billingErrorMessage = error.localizedDescription
        }
    }

    private func dedupe<T, K: Hashable>(_ values: [T], by keyPath: KeyPath<T, K>) -> [T] {
        var seen = Set<K>()
        return values.filter { value in
            seen.insert(value[keyPath: keyPath]).inserted
        }
    }
}
