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
                    accountPanel
                    modelPanel
                    billingPanel
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
            Text(model.accountSubtitle)
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
                InfoRow(label: "Name", value: model.accountDisplayName)
                if let email = model.accountEmail {
                    InfoRow(label: "Email", value: email)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(.thinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    @ViewBuilder
    private var modelPanel: some View {
        if model.session != nil, let access = model.modelAccess {
            VStack(alignment: .leading, spacing: 12) {
                Text("Assistant Model")
                    .font(.headline)
                InfoRow(label: "Current", value: model.modelDisplayName(access.currentModelLabel))
                if model.canChangeModel {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Select model")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Menu {
                            ForEach(model.selectableModels) { option in
                                Button {
                                    Task {
                                        await model.updatePreferredModel(option.model)
                                    }
                                } label: {
                                    Label(
                                        model.modelDisplayName(option.label),
                                        systemImage: option.model == access.currentModel
                                            ? "checkmark"
                                            : "circle"
                                    )
                                }
                                .disabled(model.modelSelectionInProgress)
                            }
                        } label: {
                            HStack {
                                Text(model.selectedModelTitle)
                                Spacer()
                                if model.modelSelectionInProgress {
                                    ProgressView()
                                } else {
                                    Image(systemName: "chevron.down")
                                        .font(.caption.weight(.semibold))
                                }
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                } else if let reason = model.modelReadOnlyReason {
                    Text(reason)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                if let error = model.modelErrorMessage {
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
                        InfoRow(label: model.identityLabel(identity), value: model.identityIdentifier(identity))
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
    @Published var modelSelectionInProgress = false
    @Published var errorMessage: String?
    @Published var billingErrorMessage: String?
    @Published var modelErrorMessage: String?

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

    var accountDisplayName: String {
        firstNonBlank(session?.account.displayName, session?.account.email, primaryIdentity?.identifier)
            ?? "BlueChat account"
    }

    var accountSubtitle: String {
        firstNonBlank(session?.account.email, primaryIdentity?.identifier)
            ?? "App Clip session"
    }

    var accountEmail: String? {
        firstNonBlank(session?.account.email)
    }

    var selectableModels: [WebsiteModelOption] {
        (modelAccess?.availableModels ?? [])
            .filter { $0.enabled && $0.model != "local" }
    }

    var canChangeModel: Bool {
        modelAccess?.isPremium == true
            && modelAccess?.modelSelectionConfigurable == true
            && !selectableModels.isEmpty
    }

    var selectedModelTitle: String {
        guard let access = modelAccess else {
            return "Select model"
        }
        return selectableModels
            .first(where: { $0.model == access.currentModel })
            .map { modelDisplayName($0.label) }
            ?? modelDisplayName(access.currentModelLabel)
    }

    var modelReadOnlyReason: String? {
        guard let access = modelAccess else {
            return nil
        }
        if !access.isPremium {
            return access.readOnlyReason ?? "Free accounts use the included model."
        }
        if access.modelSelectionConfigurable != true {
            return access.readOnlyReason ?? "Model selection is managed on the website."
        }
        return nil
    }

    var linkedIdentities: [WebsiteAccountIdentity] {
        dedupe(session?.linkedAccounts.integrations.flatMap { $0.link.identities } ?? [], by: \.id)
    }

    var linkedIntegrationAccounts: [WebsiteLinkedIntegrationAccount] {
        dedupe(session?.linkedAccounts.integrations.flatMap(\.linkedAccounts) ?? [], by: \.id)
    }

    var modelAccess: WebsiteModelAccessSummary? {
        session?.linkedAccounts.integrations.compactMap(\.modelAccess).first
    }

    var billingSummary: String {
        guard let session else {
            return "Premium is available through Apple."
        }
        if session.subscription.isPremium {
            if isPremiumManagedOutsideApple {
                return "Premium is active. Billing is managed on the website."
            }
            return "Premium model access is enabled through Apple."
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
        if isPremiumManagedOutsideApple {
            return "Managed on Website"
        }
        return session?.subscription.isPremium == true ? "Premium active" : "Upgrade"
    }

    var canPurchase: Bool {
        session != nil && product != nil && !purchaseInProgress && session?.subscription.isPremium != true
    }

    private var primaryIdentity: WebsiteAccountIdentity? {
        linkedIdentities.first
    }

    private var isPremiumManagedOutsideApple: Bool {
        session?.subscription.isPremium == true && !hasAppleSubscription
    }

    private var hasAppleSubscription: Bool {
        session?.subscription.subscriptions.contains {
            firstNonBlank($0.provider)?.lowercased() == "apple"
        } == true
    }

    func handleInvocation(_ url: URL) {
        guard let token = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first(where: { $0.name == "token" })?
            .value
        else {
            errorMessage = session == nil ? "Missing link token." : nil
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
        firstNonBlank(account.email, account.accountKey, account.label) ?? "Linked account"
    }

    func identityIdentifier(_ identity: WebsiteAccountIdentity) -> String {
        firstNonBlank(identity.identifier, identity.normalizedIdentifier) ?? "Linked address"
    }

    func modelDisplayName(_ value: String) -> String {
        firstNonBlank(value) ?? "Unknown"
    }

    func updatePreferredModel(_ modelKey: String) async {
        guard let session else {
            return
        }
        guard modelKey != modelAccess?.currentModel else {
            return
        }
        modelSelectionInProgress = true
        modelErrorMessage = nil
        defer {
            modelSelectionInProgress = false
        }
        do {
            _ = try await client.updatePreferredModel(
                sessionToken: session.sessionToken,
                model: modelKey
            )
            let refreshed = try await client.getSession(sessionToken: session.sessionToken)
            self.session = refreshed
            await loadProductIfNeeded(for: refreshed)
        } catch {
            modelErrorMessage = error.localizedDescription
        }
    }

    private func createSession(linkToken: String) async {
        isLoading = true
        errorMessage = nil
        billingErrorMessage = nil
        modelErrorMessage = nil
        defer {
            isLoading = false
        }
        do {
            let session = try await client.createSession(linkToken: linkToken)
            UserDefaults.standard.set(session.sessionToken, forKey: sessionTokenKey)
            self.session = session
            errorMessage = nil
            await loadProductIfNeeded(for: session)
        } catch {
            setBootstrapError(error)
        }
    }

    private func loadSession(sessionToken: String) async {
        isLoading = true
        errorMessage = nil
        billingErrorMessage = nil
        modelErrorMessage = nil
        defer {
            isLoading = false
        }
        do {
            let session = try await client.getSession(sessionToken: sessionToken)
            self.session = session
            errorMessage = nil
            await loadProductIfNeeded(for: session)
        } catch {
            if UserDefaults.standard.string(forKey: sessionTokenKey) == sessionToken {
                UserDefaults.standard.removeObject(forKey: sessionTokenKey)
            }
            setBootstrapError(error)
        }
    }

    private func setBootstrapError(_ error: Error) {
        if session == nil {
            errorMessage = error.localizedDescription
        } else {
            errorMessage = nil
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

    private func firstNonBlank(_ values: String?...) -> String? {
        values
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty }
    }

    private func dedupe<T, K: Hashable>(_ values: [T], by keyPath: KeyPath<T, K>) -> [T] {
        var seen = Set<K>()
        return values.filter { value in
            seen.insert(value[keyPath: keyPath]).inserted
        }
    }
}
