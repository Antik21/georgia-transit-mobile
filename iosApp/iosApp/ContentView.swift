import SwiftUI
import Shared

struct ContentView: View {
    var body: some View {
        ComposeViewController()
    }
}

private struct ComposeViewController: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            mapViewFactory: { MapLibreMapViewBridge.makeView() },
            updateMapView: { view, latitude, longitude, zoom in
                MapLibreMapViewBridge.update(
                    view: view,
                    latitude: latitude.doubleValue,
                    longitude: longitude.doubleValue,
                    zoom: zoom.doubleValue
                )
            }
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
