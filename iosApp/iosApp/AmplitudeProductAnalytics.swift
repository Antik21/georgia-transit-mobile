import AmplitudeSwift
import Foundation
import Shared

final class AmplitudeProductAnalytics: NSObject, NativeAnalyticsSink {
    private let amplitude: Amplitude

    init?(deviceId: String) {
#if SANDBOX
        let apiKey = (Bundle.main.object(forInfoDictionaryKey: "AmplitudeApiKey") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !apiKey.isEmpty, !apiKey.contains("$(") else { return nil }
#else
        let apiKey = NativeAmplitudeConfiguration.shared.ProductionApiKey
#endif
        amplitude = Amplitude(configuration: Configuration(
            apiKey: apiKey,
            trackingOptions: TrackingOptions().disableTrackIDFV().disableTrackIpAddress()
                .disableTrackCity().disableTrackDMA().disableTrackRegion().disableTrackCountry(),
            enableCoppaControl: true,
            minTimeBetweenSessionsMillis: Int(NativeAmplitudeConfiguration.shared.SessionIdleMillis),
            autocapture: [.appLifecycles],
            enableAutoCaptureRemoteConfig: false,
            enableDiagnostics: false,
            deviceId: deviceId
        ))
        super.init()
    }

    func send(event: NativeAnalyticsEvent) {
        if event.isIdentify {
            amplitude.identify(
                userProperties: event.userProperties,
                options: EventOptions(timestamp: event.timestamp, insertId: event.insertId)
            )
        } else {
            amplitude.track(event: BaseEvent(
                timestamp: event.timestamp,
                insertId: event.insertId,
                eventType: event.name,
                eventProperties: event.properties,
                userProperties: event.userProperties
            ))
        }
    }
}
