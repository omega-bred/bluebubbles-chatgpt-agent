import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "message.fill")
                .font(.system(size: 48))
                .foregroundStyle(.blue)
            Text("BlueChatAI")
                .font(.largeTitle.weight(.semibold))
            Text("Open a BlueChatAI link from Messages to continue.")
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}
