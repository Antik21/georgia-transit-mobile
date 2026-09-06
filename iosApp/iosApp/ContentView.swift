import SwiftUI
import Shared

struct ContentView: View {
    var body: some View {
        ComposeViewController().ignoresSafeArea(.all)
    }
}

private struct ComposeViewController: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
