import BlueChatAgentClient
import SwiftUI

struct ClipRootView: View {
    @ObservedObject var model: ClipViewModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    statusPanel
                    if model.isConversationSettingsSession {
                        conversationSettingsPanel
                    } else {
                        usagePanel
                        accountPanel
                        modelPanel
                        if model.session != nil {
                            billingPanel
                        }
                        linkedServicesPanel
                        linkedAddressesPanel
                    }
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
        .appClipPanel()
    }

    @ViewBuilder
    private var billingPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Billing")
                .font(.headline)
            Text(model.billingSummary)
                .foregroundStyle(.secondary)
            Text(model.billingManagementTitle)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(Color.secondary.opacity(0.12))
                .clipShape(Capsule())
        }
        .appClipPanel()
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
            .appClipPanel()
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
                if let error = model.modelErrorMessage {
                    Text(error)
                        .font(.callout)
                        .foregroundStyle(.red)
                }
            }
            .appClipPanel()
        }
    }

    @ViewBuilder
    private var conversationSettingsPanel: some View {
        if let settings = model.conversationSettings {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Response Style")
                        .font(.headline)
                    Text(settings.currentResponsivenessLabel)
                        .font(.title3.weight(.semibold))
                    Text("Choose how often BlueChatAI participates in this conversation.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                VStack(spacing: 10) {
                    ForEach(model.selectableResponsivenessOptions, id: \.responsiveness) { option in
                        Button {
                            Task {
                                await model.updateConversationResponsiveness(option.responsiveness.rawValue)
                            }
                        } label: {
                            HStack(alignment: .top, spacing: 12) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(option.label)
                                        .font(.callout.weight(.semibold))
                                    Text(option.description)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 8)
                                if model.responsivenessSelectionInProgress == option.responsiveness.rawValue {
                                    ProgressView()
                                } else {
                                    Image(
                                        systemName: model.isSelectedResponsiveness(option)
                                            ? "checkmark.circle.fill"
                                            : "circle"
                                    )
                                    .foregroundStyle(model.isSelectedResponsiveness(option) ? .blue : .secondary)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(
                                RoundedRectangle(cornerRadius: 8)
                                    .fill(model.isSelectedResponsiveness(option) ? Color.blue.opacity(0.14) : Color(.secondarySystemBackground))
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(model.isSelectedResponsiveness(option) ? Color.blue.opacity(0.6) : Color.secondary.opacity(0.16))
                            )
                        }
                        .buttonStyle(.plain)
                        .disabled(
                            model.responsivenessSelectionInProgress != nil
                                || model.isSelectedResponsiveness(option)
                        )
                    }
                }
                if let error = model.modelErrorMessage {
                    Text(error)
                        .font(.callout)
                        .foregroundStyle(.red)
                }
            }
            .appClipPanel()

            VStack(alignment: .leading, spacing: 12) {
                Text("Conversation")
                    .font(.headline)
                InfoRow(label: "Name", value: settings.conversation.displayName)
                InfoRow(label: "Type", value: settings.conversation.isGroup == true ? "Group chat" : "Direct chat")
                InfoRow(label: "Participants", value: model.participantCountTitle)
                if let identifier = model.conversationIdentifier {
                    InfoRow(label: "Identifier", value: identifier)
                }
                if !model.conversationParticipants.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("People")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        ForEach(model.conversationParticipants, id: \.address) { participant in
                            Text(participant.address)
                                .font(.callout)
                                .textSelection(.enabled)
                        }
                    }
                }
            }
            .appClipPanel()
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
            .appClipPanel()
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
            .appClipPanel()
        }
    }
}

private extension View {
    func appClipPanel() -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(.thinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 8))
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
    @Published var isLoading = false
    @Published var modelSelectionInProgress = false
    @Published var responsivenessSelectionInProgress: String?
    @Published var errorMessage: String?
    @Published var modelErrorMessage: String?

    private let sessionTokenKey = "bluechat.appclip.session-token"

    private enum BootstrapSource: String {
        case link
        case stored
    }

    init() {
        GeneratedAPIConfiguration.configure()
    }

    var title: String {
        if session == nil {
            return errorMessage == nil ? "Waiting for link" : "Link needs refresh"
        }
        if isConversationSettingsSession {
            return "Conversation settings"
        }
        return session?.subscription.isPremium == true ? "Premium is active" : "Free access"
    }

    var subtitle: String {
        if session == nil {
            return errorMessage == nil
                ? "Open your BlueChatAI link from Messages."
                : "Ask BlueChatAI to send a fresh link, then open it from Messages."
        }
        if isConversationSettingsSession {
            return "Choose how BlueChatAI participates in this chat."
        }
        return "Your account is ready."
    }

    var accountDisplayName: String {
        firstNonBlank(session?.account.displayName, session?.account.email, primaryIdentity?.identifier)
            ?? "BlueChat account"
    }

    var accountSubtitle: String {
        if isConversationSettingsSession {
            return conversationDisplayName
        }
        return firstNonBlank(session?.account.email, primaryIdentity?.identifier)
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

    var isConversationSettingsSession: Bool {
        session?.purpose == .conversationSettings
    }

    var conversationSettings: ConversationSettingsResponse? {
        session?.conversationSettings
    }

    var selectableResponsivenessOptions: [ConversationResponsivenessOption] {
        (conversationSettings?.options ?? []).filter(\.enabled)
    }

    var conversationDisplayName: String {
        firstNonBlank(conversationSettings?.conversation.displayName) ?? "Conversation settings"
    }

    var conversationIdentifier: String? {
        firstNonBlank(conversationSettings?.conversation.chatIdentifier)
    }

    var participantCountTitle: String {
        let count = conversationSettings?.conversation.participantCount ?? 0
        if count <= 0 {
            return "Participants unavailable"
        }
        return count == 1 ? "1 participant" : "\(count) participants"
    }

    var conversationParticipants: [ConversationParticipantSummary] {
        Array((conversationSettings?.conversation.participants ?? []).prefix(8))
    }

    var billingSummary: String {
        guard let session else {
            return "Premium can be managed from the BlueChatAI app or website."
        }
        if session.subscription.isPremium {
            if isPremiumManagedOutsideApple {
                return "Premium is active. Billing is managed on the website."
            }
            if hasAppleSubscription {
                return "Premium is active through Apple in the BlueChatAI app."
            }
            return "Premium model access is enabled."
        }
        return "Premium upgrades are available in the BlueChatAI app or website."
    }

    var billingManagementTitle: String {
        if session?.subscription.isPremium == true {
            return isPremiumManagedOutsideApple ? "Managed on Website" : "Premium active"
        }
        return "Open the app or website to upgrade"
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
            errorMessage = session == nil
                ? "This link is missing its token. Ask BlueChatAI to send a fresh link."
                : nil
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
        guard session.purpose == .accountLink else {
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

    func isSelectedResponsiveness(_ option: ConversationResponsivenessOption) -> Bool {
        option.responsiveness.rawValue == conversationSettings?.currentResponsiveness.rawValue
    }

    func updateConversationResponsiveness(_ responsivenessKey: String) async {
        guard let session else {
            return
        }
        guard session.purpose == .conversationSettings else {
            return
        }
        guard responsivenessKey != conversationSettings?.currentResponsiveness.rawValue else {
            return
        }
        guard let generatedResponsiveness = ConversationSettingsUpdateRequest.Responsiveness(rawValue: responsivenessKey) else {
            modelErrorMessage = "This response style is not available in the App Clip yet."
            return
        }
        responsivenessSelectionInProgress = responsivenessKey
        modelErrorMessage = nil
        defer {
            responsivenessSelectionInProgress = nil
        }
        do {
            trackAppClipEvent(
                "appclip_conversation_responsiveness_started",
                properties: eventContext(for: session).merging(["responsiveness": responsivenessKey]) { _, new in new }
            )
            let response = try await GeneratedAPIConfiguration.executeWithSession(session.sessionToken) {
                ConversationSettingsAPI.conversationSettingsUpdateResponsivenessWithRequestBuilder(
                    conversationSettingsUpdateRequest: ConversationSettingsUpdateRequest(
                        responsiveness: generatedResponsiveness
                    )
                )
            }
            let refreshed = session.replacing(conversationSettings: response.settings)
            self.session = refreshed
            trackAppClipEvent(
                "appclip_conversation_responsiveness_updated",
                properties: eventContext(for: refreshed).merging(["responsiveness": responsivenessKey]) { _, new in new }
            )
        } catch {
            trackAppClipEvent(
                "appclip_conversation_responsiveness_failed",
                properties: eventContext(for: session).merging(["responsiveness": responsivenessKey]) { _, new in new }
            )
            modelErrorMessage = error.localizedDescription
        }
    }

    private func createSession(linkToken: String) async {
        isLoading = true
        errorMessage = nil
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
            trackAppClipEvent(
                "appclip_session_created",
                properties: eventContext(for: session).merging(["launch_source": "link"]) { _, new in new },
                sessionToken: session.sessionToken
            )
        } catch {
            setBootstrapError(error, source: .link)
        }
    }

    private func loadSession(sessionToken: String) async {
        isLoading = true
        errorMessage = nil
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
            trackAppClipEvent(
                "appclip_session_restored",
                properties: eventContext(for: session).merging(["launch_source": "stored"]) { _, new in new },
                sessionToken: session.sessionToken
            )
        } catch {
            if UserDefaults.standard.string(forKey: sessionTokenKey) == sessionToken {
                UserDefaults.standard.removeObject(forKey: sessionTokenKey)
            }
            setBootstrapError(error, source: .stored)
        }
    }

    private func setBootstrapError(_ error: Error, source: BootstrapSource) {
        trackBootstrapFailure(error, source: source)
        if session == nil {
            errorMessage = bootstrapErrorMessage(for: error, source: source)
        } else {
            errorMessage = nil
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

    private func trackBootstrapFailure(_ error: Error, source: BootstrapSource) {
        let diagnostic = bootstrapDiagnostic(for: error)
        var properties = [
            "launch_source": source.rawValue,
            "reason": diagnostic.reason
        ]
        if let status = diagnostic.status {
            properties["http_status"] = String(status)
        }
        Task {
            GeneratedAPIConfiguration.configure()
            _ = try? await AppClipAPI.appClipCreateBootstrapEvent(
                appClipEventRequest: GeneratedAPIConfiguration.appClipEventRequest(
                    eventName: "appclip_bootstrap_failed",
                    properties: properties
                )
            )
        }
    }

    private func bootstrapErrorMessage(for error: Error, source: BootstrapSource) -> String? {
        let diagnostic = bootstrapDiagnostic(for: error)
        if source == .stored, ["invalid_session", "forbidden", "not_found", "expired"].contains(diagnostic.reason) {
            return nil
        }
        switch diagnostic.reason {
        case "network":
            return "BlueChatAI could not be reached. Check your connection and open the link again."
        case "bad_request", "not_found", "expired", "conflict":
            return "This BlueChatAI link is expired or already used. Ask BlueChatAI to send a fresh link."
        case "decode":
            return "BlueChatAI returned account info this App Clip cannot read yet. Install the latest TestFlight build and try again."
        case "server":
            return "BlueChatAI could not open this link right now. Try again in a moment."
        default:
            return "BlueChatAI could not open this link. Ask BlueChatAI to send a fresh link."
        }
    }

    private func bootstrapDiagnostic(for error: Error) -> (status: Int?, reason: String) {
        guard case let ErrorResponse.error(status, _, _, underlying) = error else {
            return (nil, "local")
        }
        if status == 200 {
            return (status, "decode")
        }
        switch status {
        case -2:
            return (status, "missing_response")
        case -1:
            return (status, "network")
        case 400:
            return (status, "bad_request")
        case 401:
            return (status, "invalid_session")
        case 403:
            return (status, "forbidden")
        case 404:
            return (status, "not_found")
        case 409:
            return (status, "conflict")
        case 410:
            return (status, "expired")
        case 500 ... 599:
            return (status, "server")
        default:
            if underlying is DecodingError {
                return (status, "decode")
            }
            return (status, "http_error")
        }
    }

    private func eventContext(for session: AppClipSessionResponse) -> [String: String] {
        var properties: [String: String] = [
            "purpose": session.purpose.rawValue,
            "is_premium": session.subscription.isPremium ? "true" : "false",
            "billing_source": session.subscription.entitlementSource
        ]
        if let model = session.linkedAccounts.integrations.compactMap(\.modelAccess).first?.currentModel {
            properties["model"] = model
        }
        if let responsiveness = session.conversationSettings?.currentResponsiveness.rawValue {
            properties["responsiveness"] = responsiveness
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
