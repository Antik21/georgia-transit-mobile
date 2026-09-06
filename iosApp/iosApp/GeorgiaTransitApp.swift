import SwiftUI
import Shared

@main
struct GeorgiaTransitApp: App {
    init() {
        GeorgiaTransitKoinKt.initGeorgiaTransitKoin()
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
