# iOS host

`iosApp` owns SwiftUI lifecycle and embeds the shared `ComposeUIViewController`. `iosMain` owns Darwin/UIKit adapter implementations. Product navigation and screens stay common.

Foundation, SwiftUI, UIKit, CoreLocation, and map SDK types must not leak into domain/presentation. An iOS build or simulator claim requires macOS, Xcode, the requested runtime, and an explicit simulator. Windows task configuration is not runtime evidence.

