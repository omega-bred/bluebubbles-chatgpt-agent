import SwiftUI

@main
struct BlueChatClipApp: App {
    @StateObject private var model = ClipViewModel()

    var body: some Scene {
        WindowGroup {
            ClipRootView(model: model)
                .onOpenURL { url in
                    model.handleInvocation(url)
                }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL {
                        model.handleInvocation(url)
                    }
                }
        }
    }
}
