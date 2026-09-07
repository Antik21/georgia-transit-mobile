import SwiftUI
import Shared

@main
struct GeorgiaTransitApp: App {
    init() {
#if DEBUG
        DebugNetworkInspector.install()
#endif
        GeorgiaTransitKoinKt.doInitGeorgiaTransitKoin(
            selectedCityStore: IosSelectedCityStore(),
            runtimeConfigurationSource: IosRuntimeBootstrapConfigurationSource()
        )
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
