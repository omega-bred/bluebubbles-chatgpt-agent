import BlueChatAgentClient
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
                    usagePanel
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

    @ViewBuilder
    private var usagePanel: some View {
        if model.session != nil, let usage = model.primaryUsageLimit {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 14) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Usage")
                            .font(.headline)
                        Text(model.usageTitle(usage))
                            .font(.subheadline.weight(.semibold))
                        Text(model.usageSummary(usage))
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    UsageBadge(percentage: usage.percentage)
                }
                UsageBar(percentage: usage.percentage)
                HStack(spacing: 10) {
                    UsageMetric(label: "Used", value: model.formatCount(usage.used))
                    UsageMetric(label: "Remaining", value: model.formatCount(usage.remaining))
                    UsageMetric(label: "Limit", value: model.formatCount(usage.limit))
                }
                Text("Resets \(model.formatDate(usage.windowEnd))")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(
                LinearGradient(
                    colors: [
                        Color.green.opacity(0.18),
                        Color.blue.opacity(0.10),
                        Color(.secondarySystemBackground)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .clipShape(RoundedRectangle(cornerRadius: 8))
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
            if model.canRestorePurchases {
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
                .disabled(model.restoreInProgress)
            }
            if let managementURL = model.appleSubscriptionManagementURL {
                Link(destination: managementURL) {
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
                            ForEach(model.selectableModels, id: \.model) { option in
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
                if model.canChangeVerbosity {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Response style")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Menu {
                            ForEach(model.selectableVerbosityOptions, id: \.verbosity) { option in
                                Button {
                                    Task {
                                        await model.updateModelVerbosity(option.verbosity.rawValue)
                                    }
                                } label: {
                                    Label(
                                        model.verbosityDisplayName(option.label),
                                        systemImage: option.verbosity.rawValue == access.currentVerbosity.rawValue
                                            ? "checkmark"
                                            : "circle"
                                    )
                                }
                                .disabled(model.verbositySelectionInProgress)
                            }
                        } label: {
                            HStack {
                                Text(model.selectedVerbosityTitle)
                                Spacer()
                                if model.verbositySelectionInProgress {
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
                    ForEach(model.linkedIntegrationAccounts, id: \.accountKey) { account in
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
                    ForEach(model.linkedIdentities, id: \.normalizedIdentifier) { identity in
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

private struct UsageBadge: View {
    let percentage: Double

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.secondary.opacity(0.18), lineWidth: 9)
            Circle()
                .trim(from: 0, to: max(0, min(1, percentage)))
                .stroke(
                    AngularGradient(
                        colors: [.green, .blue, .green],
                        center: .center
                    ),
                    style: StrokeStyle(lineWidth: 9, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
            VStack(spacing: 1) {
                Text("\(Int((max(0, min(1, percentage)) * 100).rounded()))%")
                    .font(.headline.weight(.bold))
                Text("used")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: 72, height: 72)
        .accessibilityLabel("\(Int((max(0, min(1, percentage)) * 100).rounded())) percent used")
    }
}

private struct UsageBar: View {
    let percentage: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.secondary.opacity(0.16))
                Capsule()
                    .fill(
                        LinearGradient(
                            colors: [.green, .blue],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .frame(width: fillWidth(in: proxy.size.width))
            }
        }
        .frame(height: 10)
        .accessibilityHidden(true)
    }

    private func fillWidth(in width: CGFloat) -> CGFloat {
        let value = max(0, min(1, percentage))
        return value <= 0 ? 0 : max(8, width * value)
    }
}

private struct UsageMetric: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.caption2.weight(.bold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.caption.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
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
    @Published var restoreInProgress = false
    @Published var modelSelectionInProgress = false
    @Published var verbositySelectionInProgress = false
    @Published var errorMessage: String?
    @Published var billingErrorMessage: String?
    @Published var modelErrorMessage: String?

    private let storeKit = StoreKitManager()
    private let sessionTokenKey = "bluechat.appclip.session-token"
    private var transactionUpdatesTask: Task<Void, Never>?

    init() {
        GeneratedAPIConfiguration.configure()
        observeStoreKitTransactionUpdates()
    }

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

    var selectableVerbosityOptions: [WebsiteModelVerbosityOption] {
        (modelAccess?.availableVerbosityOptions ?? [])
            .filter(\.enabled)
    }

    var canChangeModel: Bool {
        modelAccess?.isPremium == true
            && modelAccess?.modelSelectionConfigurable == true
            && !selectableModels.isEmpty
    }

    var canChangeVerbosity: Bool {
        modelAccess?.verbositySelectionConfigurable == true
            && !selectableVerbosityOptions.isEmpty
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

    var selectedVerbosityTitle: String {
        guard let access = modelAccess else {
            return "Balanced"
        }
        return selectableVerbosityOptions
            .first(where: { $0.verbosity.rawValue == access.currentVerbosity.rawValue })
            .map { verbosityDisplayName($0.label) }
            ?? verbosityDisplayName(access.currentVerbosityLabel)
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
        dedupe(session?.linkedAccounts.integrations.flatMap { $0.link.identities } ?? []) {
            $0.type.rawValue + ":" + $0.normalizedIdentifier
        }
    }

    var linkedIntegrationAccounts: [WebsiteLinkedIntegrationAccount] {
        dedupe(session?.linkedAccounts.integrations.flatMap(\.linkedAccounts) ?? []) {
            $0.type.rawValue + ":" + $0.accountKey
        }
    }

    var usageLimits: [WebsiteUsageLimitSummary] {
        session?.linkedAccounts.usageLimits ?? []
    }

    var primaryUsageLimit: WebsiteUsageLimitSummary? {
        usageLimits.first
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

    var restoreButtonTitle: String {
        restoreInProgress ? "Checking Apple Purchases" : "Restore Apple Purchase"
    }

    var canPurchase: Bool {
        session != nil && product != nil && !purchaseInProgress && session?.subscription.isPremium != true
    }

    var canRestorePurchases: Bool {
        session != nil
            && session?.subscription.isPremium != true
            && session?.storekitProductIds.isEmpty == false
    }

    var appleSubscriptionManagementURL: URL? {
        hasAppleSubscription ? URL(string: "https://apps.apple.com/account/subscriptions") : nil
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
            trackAppClipEvent("appclip_purchase_started", properties: eventContext(for: session))
            let purchase = try await storeKit.purchase(product: product, appAccountToken: session.appAccountToken)
            let updated = try await validateStoreKitPurchase(purchase, session: session, productId: product.id)
            await purchase.transaction.finish()
            self.session = session.replacing(subscription: updated)
            trackAppClipEvent(
                "appclip_purchase_completed",
                properties: eventContext(for: self.session ?? session)
            )
        } catch {
            trackAppClipEvent(
                "appclip_purchase_failed",
                properties: eventContext(for: session)
            )
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
            trackAppClipEvent("appclip_purchase_restore_started", properties: eventContext(for: session))
            let restored = try await syncCurrentAppleEntitlements(for: session)
            if restored {
                trackAppClipEvent(
                    "appclip_purchase_restore_completed",
                    properties: eventContext(for: self.session ?? session)
                )
            } else {
                billingErrorMessage = "No active Apple subscription was found for this account."
                trackAppClipEvent(
                    "appclip_purchase_restore_empty",
                    properties: eventContext(for: session)
                )
            }
        } catch {
            trackAppClipEvent(
                "appclip_purchase_restore_failed",
                properties: eventContext(for: session)
            )
            billingErrorMessage = error.localizedDescription
        }
    }

    func identityLabel(_ identity: WebsiteAccountIdentity) -> String {
        switch identity.type {
        case .imessageEmail:
            return "BlueChat email"
        case .imessagePhone:
            return "BlueChat phone"
        case .lxmfAddress:
            return "LXMF address"
        }
    }

    func integrationLabel(_ account: WebsiteLinkedIntegrationAccount) -> String {
        switch account.type {
        case .gcal:
            return "Google Calendar"
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

    func verbosityDisplayName(_ value: String) -> String {
        firstNonBlank(value) ?? "Balanced"
    }

    func usageTitle(_ usage: WebsiteUsageLimitSummary) -> String {
        firstNonBlank(usage.limitLabel) ?? "Monthly assistant responses"
    }

    func usageSummary(_ usage: WebsiteUsageLimitSummary) -> String {
        if usage.exhausted {
            return "Monthly responses are used up until the reset."
        }
        return "\(formatCount(usage.remaining)) responses remain this month."
    }

    func formatCount(_ value: Int64) -> String {
        NumberFormatter.localizedString(from: NSNumber(value: value), number: .decimal)
    }

    func formatDate(_ value: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: value)
    }

    func updatePreferredModel(_ modelKey: String) async {
        guard let session else {
            return
        }
        guard modelKey != modelAccess?.currentModel else {
            return
        }
        guard let generatedModel = WebsiteModelSelectionRequest.Model(rawValue: modelKey) else {
            modelErrorMessage = "This model is not available in the App Clip yet."
            return
        }
        modelSelectionInProgress = true
        modelErrorMessage = nil
        defer {
            modelSelectionInProgress = false
        }
        do {
            trackAppClipEvent(
                "appclip_model_selection_started",
                properties: eventContext(for: session).merging(["model": modelKey]) { _, new in new }
            )
            _ = try await GeneratedAPIConfiguration.executeWithSession(session.sessionToken) {
                WebsiteAccountAPI.websiteAccountUpdateModelWithRequestBuilder(
                    websiteModelSelectionRequest: WebsiteModelSelectionRequest(model: generatedModel)
                )
            }
            let refreshed = try await GeneratedAPIConfiguration.executeWithSession(session.sessionToken) {
                AppClipAPI.appClipGetSessionWithRequestBuilder()
            }
            self.session = refreshed
            await loadProductIfNeeded(for: refreshed)
            trackAppClipEvent(
                "appclip_model_selection_updated",
                properties: eventContext(for: refreshed).merging(["model": modelKey]) { _, new in new }
            )
        } catch {
            trackAppClipEvent(
                "appclip_model_selection_failed",
                properties: eventContext(for: session).merging(["model": modelKey]) { _, new in new }
            )
            modelErrorMessage = error.localizedDescription
        }
    }

    func updateModelVerbosity(_ verbosityKey: String) async {
        guard let session else {
            return
        }
        guard verbosityKey != modelAccess?.currentVerbosity.rawValue else {
            return
        }
        guard let generatedVerbosity = WebsiteModelSelectionRequest.Verbosity(rawValue: verbosityKey) else {
            modelErrorMessage = "This response style is not available in the App Clip yet."
            return
        }
        verbositySelectionInProgress = true
        modelErrorMessage = nil
        defer {
            verbositySelectionInProgress = false
        }
        do {
            trackAppClipEvent(
                "appclip_model_verbosity_started",
                properties: eventContext(for: session).merging(["verbosity": verbosityKey]) { _, new in new }
            )
            _ = try await GeneratedAPIConfiguration.executeWithSession(session.sessionToken) {
                WebsiteAccountAPI.websiteAccountUpdateModelWithRequestBuilder(
                    websiteModelSelectionRequest: WebsiteModelSelectionRequest(verbosity: generatedVerbosity)
                )
            }
            let refreshed = try await GeneratedAPIConfiguration.executeWithSession(session.sessionToken) {
                AppClipAPI.appClipGetSessionWithRequestBuilder()
            }
            self.session = refreshed
            await loadProductIfNeeded(for: refreshed)
            trackAppClipEvent(
                "appclip_model_verbosity_updated",
                properties: eventContext(for: refreshed).merging(["verbosity": verbosityKey]) { _, new in new }
            )
        } catch {
            trackAppClipEvent(
                "appclip_model_verbosity_failed",
                properties: eventContext(for: session).merging(["verbosity": verbosityKey]) { _, new in new }
            )
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
            GeneratedAPIConfiguration.configure()
            let session = try await AppClipAPI.appClipCreateSession(
                appClipCreateSessionRequest: AppClipCreateSessionRequest(linkToken: linkToken)
            )
            UserDefaults.standard.set(session.sessionToken, forKey: sessionTokenKey)
            self.session = session
            errorMessage = nil
            await loadProductIfNeeded(for: session)
            await syncCurrentAppleEntitlementsSilently(for: self.session ?? session)
            trackAppClipEvent(
                "appclip_session_created",
                properties: eventContext(for: session).merging(["launch_source": "link"]) { _, new in new },
                sessionToken: session.sessionToken
            )
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
            let session = try await GeneratedAPIConfiguration.executeWithSession(sessionToken) {
                AppClipAPI.appClipGetSessionWithRequestBuilder()
            }
            self.session = session
            errorMessage = nil
            await loadProductIfNeeded(for: session)
            await syncCurrentAppleEntitlementsSilently(for: self.session ?? session)
            trackAppClipEvent(
                "appclip_session_restored",
                properties: eventContext(for: session).merging(["launch_source": "stored"]) { _, new in new },
                sessionToken: session.sessionToken
            )
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

    private func validateStoreKitPurchase(
        _ purchase: StoreKitManager.VerifiedPurchase,
        session: AppClipSessionResponse,
        productId: String? = nil
    ) async throws -> SubscriptionSummaryResponse {
        try await GeneratedAPIConfiguration.executeWithSession(session.sessionToken) {
            SubscriptionAPI.subscriptionValidateStoreKitWithRequestBuilder(
                subscriptionStoreKitTransactionRequest: SubscriptionStoreKitTransactionRequest(
                    signedTransactionInfo: purchase.jwsRepresentation,
                    productId: productId ?? purchase.transaction.productID,
                    transactionId: String(purchase.transaction.id)
                )
            )
        }
    }

    private func syncCurrentAppleEntitlementsSilently(for session: AppClipSessionResponse) async {
        guard session.subscription.isPremium != true else {
            return
        }
        do {
            let restored = try await syncCurrentAppleEntitlements(for: session)
            if restored {
                trackAppClipEvent(
                    "appclip_purchase_auto_restored",
                    properties: eventContext(for: self.session ?? session)
                )
            }
        } catch {
            trackAppClipEvent(
                "appclip_purchase_auto_restore_failed",
                properties: eventContext(for: session)
            )
        }
    }

    private func syncCurrentAppleEntitlements(for session: AppClipSessionResponse) async throws -> Bool {
        let purchases = try await storeKit.currentEntitlements(productIds: session.storekitProductIds)
        guard
            let purchase = purchases.sorted(by: { $0.transaction.purchaseDate > $1.transaction.purchaseDate }).first
        else {
            return false
        }
        let updated = try await validateStoreKitPurchase(purchase, session: session)
        let refreshed = (self.session ?? session).replacing(subscription: updated)
        self.session = refreshed
        await loadProductIfNeeded(for: refreshed)
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
            trackAppClipEvent(
                "appclip_purchase_transaction_update_started",
                properties: eventContext(for: session)
            )
            let updated = try await validateStoreKitPurchase(purchase, session: session)
            await purchase.transaction.finish()
            let refreshed = (self.session ?? session).replacing(subscription: updated)
            self.session = refreshed
            await loadProductIfNeeded(for: refreshed)
            trackAppClipEvent(
                "appclip_purchase_transaction_update_completed",
                properties: eventContext(for: refreshed)
            )
        } catch {
            billingErrorMessage = error.localizedDescription
            trackAppClipEvent(
                "appclip_purchase_transaction_update_failed",
                properties: eventContext(for: session)
            )
        }
    }

    private func trackAppClipEvent(
        _ eventName: String,
        properties: [String: String] = [:],
        sessionToken explicitSessionToken: String? = nil
    ) {
        let token = explicitSessionToken ?? session?.sessionToken
        guard let token else {
            return
        }
        Task {
            _ = try? await GeneratedAPIConfiguration.executeWithSession(token) {
                AppClipAPI.appClipCreateEventWithRequestBuilder(
                    appClipEventRequest: GeneratedAPIConfiguration.appClipEventRequest(
                        eventName: eventName,
                        properties: properties
                    )
                )
            }
        }
    }

    private func eventContext(for session: AppClipSessionResponse) -> [String: String] {
        var properties: [String: String] = [
            "is_premium": session.subscription.isPremium ? "true" : "false",
            "billing_source": session.subscription.entitlementSource
        ]
        if let model = session.linkedAccounts.integrations.compactMap(\.modelAccess).first?.currentModel {
            properties["model"] = model
        }
        if let verbosity = session.linkedAccounts.integrations.compactMap(\.modelAccess).first?.currentVerbosity.rawValue {
            properties["verbosity"] = verbosity
        }
        if let usage = session.linkedAccounts.usageLimits.first {
            properties["usage_exhausted"] = usage.exhausted ? "true" : "false"
            properties["usage_percent"] = String(Int((max(0, min(1, usage.percentage)) * 100).rounded()))
        }
        return properties
    }

    private func firstNonBlank(_ values: String?...) -> String? {
        values
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty }
    }

    private func dedupe<T, K: Hashable>(_ values: [T], by key: (T) -> K) -> [T] {
        var seen = Set<K>()
        return values.filter { value in
            seen.insert(key(value)).inserted
        }
    }
}
