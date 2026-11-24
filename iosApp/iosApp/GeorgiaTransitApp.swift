import FirebaseAnalytics
import FirebaseCore
import FirebaseCrashlytics
import SwiftUI
import Shared
import UIKit

private final class FirebaseAppTelemetry: NSObject, AppTelemetry {
    func setSelectedCity(cityId: String?) {
        Analytics.setUserProperty(cityId, forName: "selected_city")
    }

    func recordNonFatal(issue: NonFatalIssue) {
        let error = NSError(
            domain: "com.anti.georgiatransit.handled",
            code: Int(issue.code),
            userInfo: [NSLocalizedDescriptionKey: issue.summary]
        )
        Crashlytics.crashlytics().record(error: error)
    }
}

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

private final class GeorgiaTransitAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        FirebaseApp.configure()
#if SANDBOX
        DebugNetworkInspector.install()
#endif
        let deviceId = IosAnalyticsIdentityKt.iosAnalyticsDeviceId()
        GeorgiaTransitKoinKt.doInitGeorgiaTransitKoin(
            selectedCityStore: IosSelectedCityStore(),
            runtimeConfigurationSource: IosRuntimeBootstrapConfigurationSource(),
            transitCacheStore: IosTransitCacheStore(),
            bffEndpointConfiguration: AppEnvironment.bffEndpointConfiguration(),
            appTelemetry: FirebaseAppTelemetry(),
            analyticsClient: AnalyticsClient.ios,
            analyticsDeviceId: deviceId,
            nativeAnalytics: AmplitudeProductAnalytics(deviceId: deviceId)
        )
        return true
    }
}

@main
struct GeorgiaTransitApp: App {
    @UIApplicationDelegateAdaptor(GeorgiaTransitAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
