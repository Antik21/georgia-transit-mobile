import SwiftUI
import Shared

@main
struct GeorgiaTransitApp: App {
    init() {
        GeorgiaTransitKoinKt.doInitGeorgiaTransitKoin(
            selectedCityStore: IosSelectedCityStore(),
            runtimeConfigurationSource: IosRuntimeBootstrapConfigurationSource()
        )
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
