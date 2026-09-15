import SwiftUI
import Shared

private enum AppEnvironment {
    static func bffEndpointConfiguration() -> BffEndpointConfiguration {
#if SANDBOX
        guard
            let baseURL = Bundle.main.object(forInfoDictionaryKey: "BffBaseURL") as? String,
            !baseURL.isEmpty,
            !baseURL.contains("$(")
        else {
            fatalError("Sandbox requires a resolved BffBaseURL build setting.")
        }
        return IosBffEndpointConfigurationKt.debugIosSandboxBffEndpointConfiguration(baseUrl: baseURL)
#elseif PRODUCTION
        return IosBffEndpointConfigurationKt.productionIosBffEndpointConfiguration()
#else
#error("Build with the Sandbox or Prod configuration.")
#endif
    }
}

@main
struct GeorgiaTransitApp: App {
    init() {
#if SANDBOX
        DebugNetworkInspector.install()
#endif
        GeorgiaTransitKoinKt.doInitGeorgiaTransitKoin(
            selectedCityStore: IosSelectedCityStore(),
            runtimeConfigurationSource: IosRuntimeBootstrapConfigurationSource(),
            transitCacheStore: IosTransitCacheStore(),
            bffEndpointConfiguration: AppEnvironment.bffEndpointConfiguration()
        )
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
