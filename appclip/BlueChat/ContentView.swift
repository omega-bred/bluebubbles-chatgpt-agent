import BlueChatAgentClient
import MessageUI
import StoreKit
import SwiftUI

struct ContentView: View {
    @StateObject private var model = MainAppViewModel()
    @State private var showingMessageComposer = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    statusPanel
                    startConversationPanel
                    billingPanel
                }
                .padding(20)
            }
            .navigationTitle("BlueChatAI")
            .task {
                await model.restoreOrCreateSession()
            }
            .sheet(isPresented: $showingMessageComposer) {
                MessageComposer(
                    recipients: model.messageRecipients,
                    body: model.messageBody
                )
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("BlueChatAI", systemImage: "message.fill")
                .font(.largeTitle.weight(.semibold))
            Text("AI in the thread you already use.")
                .font(.body)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var statusPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(model.statusTitle)
                .font(.headline)
            Text(model.statusSubtitle)
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

    private var startConversationPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Start a Conversation")
                .font(.headline)
            Text(model.textingSummary)
                .foregroundStyle(.secondary)
            Button {
                openMessageComposer()
            } label: {
                Label("Text BlueChatAI", systemImage: "message.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!model.canStartConversation)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(
            LinearGradient(
                colors: [
                    Color.blue.opacity(0.18),
                    Color.green.opacity(0.10),
                    Color(.secondarySystemBackground)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

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
            Button {
                Task {
                    await model.restoreApplePurchases()
                }
            } label: {
                HStack {
                    if model.restoreInProgress {
                        ProgressView()
                    }
                    Text(model.restoreButtonTitle)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(!model.canRestorePurchases)
            if model.hasAppleSubscription {
                Link(destination: URL(string: "https://apps.apple.com/account/subscriptions")!) {
                    Text("Manage Apple Subscription")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func openMessageComposer() {
        if MFMessageComposeViewController.canSendText() {
            showingMessageComposer = true
            return
        }
        if let smsURL = model.smsURL {
            openURL(smsURL)
        }
    }
}

@MainActor
final class MainAppViewModel: ObservableObject {
    @Published private(set) var session: NativeAppSessionResponse?
    @Published private(set) var product: Product?
    @Published var isLoading = false
    @Published var purchaseInProgress = false
    @Published var restoreInProgress = false
    @Published var errorMessage: String?
    @Published var billingErrorMessage: String?

    private let storeKit = StoreKitManager()
    private let sessionTokenKey = "bluechat.native.session-token"
    private var transactionUpdatesTask: Task<Void, Never>?

    init() {
        NativeAPIConfiguration.configure()
        observeStoreKitTransactionUpdates()
    }

    var statusTitle: String {
        guard let session else {
            return isLoading ? "Preparing BlueChatAI" : "Ready to start"
        }
        return session.subscription.isPremium ? "Premium is active" : "Free access"
    }

    var statusSubtitle: String {
        guard session != nil else {
            return "Creating a private app session for Messages and billing."
        }
        return "Your Messages starter link and billing state are ready."
    }

    var textingSummary: String {
        guard let session else {
            return "Loading the current BlueChatAI number."
        }
        return "We'll prefill a message to \(session.texting.displayNumber). Send it once to connect this app session to your BlueChat thread."
    }

    var messageRecipients: [String] {
        guard let phoneNumber = session?.texting.phoneNumberE164 else {
            return []
        }
        return [phoneNumber]
    }

    var messageBody: String {
        session?.texting.defaultMessage ?? "Hi BlueChatAI, let's start."
    }

    var smsURL: URL? {
        guard let value = session?.texting.smsUrl else {
            return nil
        }
        return URL(string: value)
    }

    var canStartConversation: Bool {
        session != nil
    }

    var billingSummary: String {
        guard let session else {
            return "Loading premium options."
        }
        if session.subscription.isPremium {
            if isPremiumManagedOutsideApple {
                return "Premium is active. Billing is managed on the website."
            }
            return "Premium model access is enabled through Apple."
        }
        if let product {
            return "\(product.displayPrice) per month for premium models and higher monthly usage."
        }
        if session.storekitProductIds.isEmpty {
            return "Premium purchases are not configured yet."
        }
        return "Loading Apple purchase options."
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

    var restoreButtonTitle: String {
        restoreInProgress ? "Checking Apple Purchases" : "Restore Apple Purchase"
    }

    var canPurchase: Bool {
        product != nil
            && !purchaseInProgress
            && session?.subscription.isPremium != true
    }

    var canRestorePurchases: Bool {
        session != nil
            && !restoreInProgress
            && session?.subscription.isPremium != true
            && session?.storekitProductIds.isEmpty == false
    }

    var hasAppleSubscription: Bool {
        session?.subscription.subscriptions.contains {
            firstNonBlank($0.provider)?.lowercased() == "apple"
        } == true
    }

    private var isPremiumManagedOutsideApple: Bool {
        session?.subscription.isPremium == true && !hasAppleSubscription
    }

    func restoreOrCreateSession() async {
        if let token = UserDefaults.standard.string(forKey: sessionTokenKey) {
            do {
                try await loadSession(sessionToken: token)
                return
            } catch {
                UserDefaults.standard.removeObject(forKey: sessionTokenKey)
            }
        }
        await createSession()
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
            let purchase = try await storeKit.purchase(
                product: product,
                appAccountToken: session.appAccountToken
            )
            let updated = try await validateStoreKitPurchase(
                purchase,
                session: session,
                productId: product.id
            )
            await purchase.transaction.finish()
            self.session = session.replacing(subscription: updated)
            self.product = nil
        } catch {
            billingErrorMessage = error.localizedDescription
        }
    }

    func restoreApplePurchases() async {
        guard let session else {
            return
        }
        restoreInProgress = true
        billingErrorMessage = nil
        defer {
            restoreInProgress = false
        }
        do {
            let restored = try await syncCurrentAppleEntitlements(for: session)
            if !restored {
                billingErrorMessage = "No active Apple subscription was found for this account."
            }
        } catch {
            billingErrorMessage = error.localizedDescription
        }
    }

    private func createSession() async {
        isLoading = true
        errorMessage = nil
        billingErrorMessage = nil
        defer {
            isLoading = false
        }
        do {
            let session = try await NativeAppAPI.nativeAppCreateSession(
                nativeAppSessionCreateRequest: NativeAppSessionCreateRequest(source: "main_app")
            )
            UserDefaults.standard.set(session.sessionToken, forKey: sessionTokenKey)
            self.session = session
            await prepareBilling(for: session)
        } catch {
            errorMessage = userVisibleError(error)
        }
    }

    private func loadSession(sessionToken: String) async throws {
        isLoading = true
        errorMessage = nil
        billingErrorMessage = nil
        defer {
            isLoading = false
        }
        let session = try await NativeAPIConfiguration.executeWithSession(sessionToken) {
            NativeAppAPI.nativeAppGetSessionWithRequestBuilder()
        }
        self.session = session
        await prepareBilling(for: session)
    }

    private func prepareBilling(for session: NativeAppSessionResponse) async {
        if session.subscription.isPremium {
            product = nil
            return
        }
        await loadProduct(productIds: session.storekitProductIds)
        await syncCurrentAppleEntitlementsSilently(for: self.session ?? session)
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

    private func validateStoreKitPurchase(
        _ purchase: StoreKitManager.VerifiedPurchase,
        session: NativeAppSessionResponse,
        productId: String? = nil
    ) async throws -> SubscriptionSummaryResponse {
        try await NativeAPIConfiguration.executeWithSession(session.sessionToken) {
            SubscriptionAPI.subscriptionValidateStoreKitWithRequestBuilder(
                subscriptionStoreKitTransactionRequest: SubscriptionStoreKitTransactionRequest(
                    signedTransactionInfo: purchase.jwsRepresentation,
                    productId: productId ?? purchase.transaction.productID,
                    transactionId: String(purchase.transaction.id)
                )
            )
        }
    }

    private func syncCurrentAppleEntitlementsSilently(for session: NativeAppSessionResponse) async {
        guard session.subscription.isPremium != true else {
            return
        }
        do {
            _ = try await syncCurrentAppleEntitlements(for: session)
        } catch {
            // Silent restore is opportunistic; explicit restore surfaces failures.
        }
    }

    private func syncCurrentAppleEntitlements(for session: NativeAppSessionResponse) async throws -> Bool {
        let purchases = try await storeKit.currentEntitlements(productIds: session.storekitProductIds)
        guard
            let purchase = purchases.sorted(by: { $0.transaction.purchaseDate > $1.transaction.purchaseDate }).first
        else {
            return false
        }
        let updated = try await validateStoreKitPurchase(purchase, session: session)
        let refreshed = (self.session ?? session).replacing(subscription: updated)
        self.session = refreshed
        product = nil
        return true
    }

    private func observeStoreKitTransactionUpdates() {
        guard transactionUpdatesTask == nil else {
            return
        }
        transactionUpdatesTask = Task { [weak self] in
            for await verification in StoreKit.Transaction.updates {
                guard !Task.isCancelled else {
                    return
                }
                await self?.handleStoreKitTransactionUpdate(verification)
            }
        }
    }

    private func handleStoreKitTransactionUpdate(
        _ verification: VerificationResult<StoreKit.Transaction>
    ) async {
        guard let session else {
            return
        }
        do {
            let purchase = try storeKit.verifiedPurchase(from: verification)
            guard session.storekitProductIds.contains(purchase.transaction.productID) else {
                return
            }
            let updated = try await validateStoreKitPurchase(purchase, session: session)
            await purchase.transaction.finish()
            self.session = session.replacing(subscription: updated)
            product = nil
        } catch {
            billingErrorMessage = error.localizedDescription
        }
    }

    private func userVisibleError(_ error: Error) -> String {
        guard case let ErrorResponse.error(status, _, _, underlying) = error else {
            return error.localizedDescription
        }
        if underlying is DecodingError {
            return "BlueChatAI returned app data this build cannot read yet."
        }
        switch status {
        case -1:
            return "BlueChatAI could not be reached. Check your connection and try again."
        case 500 ... 599:
            return "BlueChatAI could not prepare the app session right now."
        default:
            return "BlueChatAI could not prepare the app session."
        }
    }

    private func firstNonBlank(_ values: String?...) -> String? {
        values
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty }
    }
}

private enum NativeAPIConfiguration {
    static func configure() {
        BlueChatAgentClientAPI.basePath = "https://bluechat.bre.land"
    }

    static func executeWithSession<T>(
        _ sessionToken: String,
        builder: () -> RequestBuilder<T>
    ) async throws -> T where T: Decodable {
        configure()
        return try await builder()
            .addHeader(name: "X-BlueChat-App-Session", value: sessionToken)
            .execute()
            .body
    }
}

private struct StoreKitManager {
    func loadProducts(productIds: [String]) async throws -> [Product] {
        try await Product.products(for: productIds)
    }

    func purchase(product: Product, appAccountToken: UUID) async throws -> VerifiedPurchase {
        let result = try await product.purchase(options: [.appAccountToken(appAccountToken)])
        switch result {
        case .success(let verification):
            return try verifiedPurchase(from: verification)
        case .userCancelled:
            throw StoreKitError.userCancelled
        case .pending:
            throw StoreKitError.pending
        @unknown default:
            throw StoreKitError.unknown
        }
    }

    func currentEntitlements(productIds: [String]) async throws -> [VerifiedPurchase] {
        let requestedIds = Set(productIds)
        var purchases: [VerifiedPurchase] = []
        for await verification in StoreKit.Transaction.currentEntitlements {
            let purchase = try verifiedPurchase(from: verification)
            if requestedIds.isEmpty || requestedIds.contains(purchase.transaction.productID) {
                purchases.append(purchase)
            }
        }
        return purchases
    }

    func verifiedPurchase(
        from verification: VerificationResult<StoreKit.Transaction>
    ) throws -> VerifiedPurchase {
        try VerifiedPurchase(
            transaction: checkVerified(verification),
            jwsRepresentation: verification.jwsRepresentation
        )
    }

    struct VerifiedPurchase {
        let transaction: StoreKit.Transaction
        let jwsRepresentation: String
    }

    private func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .verified(let value):
            return value
        case .unverified(_, let error):
            throw error
        }
    }

    enum StoreKitError: LocalizedError {
        case userCancelled
        case pending
        case unknown

        var errorDescription: String? {
            switch self {
            case .userCancelled:
                return "Purchase cancelled."
            case .pending:
                return "Purchase pending."
            case .unknown:
                return "Purchase unavailable."
            }
        }
    }
}

private struct MessageComposer: UIViewControllerRepresentable {
    let recipients: [String]
    let body: String

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let controller = MFMessageComposeViewController()
        controller.messageComposeDelegate = context.coordinator
        controller.recipients = recipients
        controller.body = body
        return controller
    }

    func updateUIViewController(_ uiViewController: MFMessageComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        private let parent: MessageComposer

        init(_ parent: MessageComposer) {
            self.parent = parent
        }

        func messageComposeViewController(
            _ controller: MFMessageComposeViewController,
            didFinishWith result: MessageComposeResult
        ) {
            controller.dismiss(animated: true)
        }
    }
}

private extension NativeAppSessionResponse {
    func replacing(subscription: SubscriptionSummaryResponse) -> NativeAppSessionResponse {
        NativeAppSessionResponse(
            sessionToken: sessionToken,
            expiresAt: expiresAt,
            accountId: accountId,
            appAccountToken: appAccountToken,
            subscription: subscription,
            storekitProductIds: storekitProductIds,
            texting: texting
        )
    }
}
