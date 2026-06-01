import StoreKit
import SwiftUI

struct ClipRootView: View {
    @ObservedObject var model: ClipViewModel

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 18) {
                header
                statusPanel
                billingPanel
                Spacer(minLength: 0)
            }
            .padding(20)
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
}

@MainActor
final class ClipViewModel: ObservableObject {
    @Published private(set) var session: AppClipSessionResponse?
    @Published private(set) var product: Product?
    @Published var isLoading = false
    @Published var purchaseInProgress = false
    @Published var errorMessage: String?

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
        errorMessage = nil
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
            errorMessage = error.localizedDescription
        }
    }

    private func createSession(linkToken: String) async {
        isLoading = true
        errorMessage = nil
        defer {
            isLoading = false
        }
        do {
            let session = try await client.createSession(linkToken: linkToken)
            UserDefaults.standard.set(session.sessionToken, forKey: sessionTokenKey)
            self.session = session
            await loadProduct(productIds: session.storekitProductIds)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func loadSession(sessionToken: String) async {
        isLoading = true
        errorMessage = nil
        defer {
            isLoading = false
        }
        do {
            let session = try await client.getSession(sessionToken: sessionToken)
            self.session = session
            await loadProduct(productIds: session.storekitProductIds)
        } catch {
            UserDefaults.standard.removeObject(forKey: sessionTokenKey)
            errorMessage = error.localizedDescription
        }
    }

    private func loadProduct(productIds: [String]) async {
        do {
            product = try await storeKit.loadProducts(productIds: productIds).first
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
