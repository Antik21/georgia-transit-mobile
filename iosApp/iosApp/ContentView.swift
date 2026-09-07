import SwiftUI
import Shared

struct ContentView: View {
    var body: some View {
#if DEBUG
        DebugNetworkInspectorOverlay()
#else
        ComposeViewController()
#endif
    }
}

struct ComposeViewController: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            mapViewFactory: { MapLibreMapViewBridge.makeView() },
            updateMapView: { view, latitude, longitude, zoom, contentLatitude, contentLongitude, userLatitude, userLongitude, accuracy, precision in
                MapLibreMapViewBridge.update(
                    view: view,
                    latitude: latitude.doubleValue,
                    longitude: longitude.doubleValue,
                    zoom: zoom.doubleValue,
                    contentLatitude: contentLatitude.doubleValue,
                    contentLongitude: contentLongitude.doubleValue,
                    userLatitude: userLatitude?.doubleValue,
                    userLongitude: userLongitude?.doubleValue,
                    accuracy: accuracy?.doubleValue,
                    isPrecise: precision?.intValue == 1
                )
            }
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
