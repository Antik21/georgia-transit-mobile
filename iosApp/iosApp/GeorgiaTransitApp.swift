import SwiftUI
import Shared

@main
struct GeorgiaTransitApp: App {
    init() {
#if DEBUG
        DebugNetworkInspector.install()
        let bffEndpoint = IosBffEndpointConfigurationKt.debugIosSimulatorBffEndpointConfiguration()
#else
        let bffEndpoint: BffEndpointConfiguration? = nil
#endif
        GeorgiaTransitKoinKt.doInitGeorgiaTransitKoin(
            selectedCityStore: IosSelectedCityStore(),
            runtimeConfigurationSource: IosRuntimeBootstrapConfigurationSource(),
            transitCacheStore: IosTransitCacheStore(),
            bffEndpointConfiguration: bffEndpoint
        )
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
