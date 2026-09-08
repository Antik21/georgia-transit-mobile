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
            mapViewFactory: { onViewportSettled, onStopTapped in
                MapLibreMapViewBridge.makeView(
                    onViewportSettled: onViewportSettled,
                    onStopTapped: onStopTapped
                )
            },
            updateMapView: { view, renderState in
                MapLibreMapViewBridge.update(view: view, renderState: renderState)
            },
            releaseMapView: { view in
                MapLibreMapViewBridge.release(view: view)
            }
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
