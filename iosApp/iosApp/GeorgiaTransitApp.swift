import SwiftUI
import Shared

@main
struct GeorgiaTransitApp: App {
    init() {
        GeorgiaTransitKoinKt.doInitGeorgiaTransitKoin()
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
