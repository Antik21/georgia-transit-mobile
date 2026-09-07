# Debug network inspector

`DEN-77` adds a local, developer-only HTTP inspector. It is deliberately a host
tooling adapter: it neither changes shared product navigation nor creates a
second BFF client. The current shell has no real BFF calls, so each host exposes
temporary smoke actions solely to verify the inspector. They never call a
transit provider or use credentials.

## Android Debug

Install an `androidApp` **Debug** build and open the ordinary **Georgia Transit**
launcher icon once; `MainActivity` remains the sole normal launcher and this
first launch registers the Debug-only dynamic app shortcut. Long-press the app
icon and select **Network inspector** (stable shortcut ID
`debug_network_inspector`). The stable root control IDs are
`debug_network_inspector_*`. QA can alternatively open the same Debug-only
activity directly with:

```text
adb shell am start -n com.denis.georgiatransit/com.denis.georgiatransit.android.DebugNetworkInspectorActivity
```

This activity is exported only in Debug for that explicit QA command; it has no
`MAIN`/`LAUNCHER` intent filter.

The inspector has three actions:

1. **Run successful HTTPS smoke** requests `https://example.com/` through the
   shared `createTransitHttpClient()` factory.
2. **Run controlled local failure** requests
   `https://127.0.0.1:1/den-77-controlled-failure` through that same factory;
   it must fail locally and make no external provider request.
3. **Open captured requests** opens Chucker's local history. Use Chucker's
   top-right **Clear** action (or its overflow **Clear all** action when shown)
   to remove every stored transaction immediately.

The debug `Application` installs the optional shared Android OkHttp seam before
Koin bootstrap. `createTransitHttpClient()` remains the only factory and keeps
its existing 5-second connect timeout, 20-second read timeout, and connection
retry behavior. Chucker is configured as an application interceptor with no
notification, Chucker-provided launcher shortcut, analytics, cloud reporter, or
automatic export/upload integration. If a developer explicitly uses Chucker's
built-in manual export/share UI, it contains only the already-sanitized local
records. The app's separate Debug-only dynamic shortcut only opens this local
inspector.

## iOS Debug

Build and launch the **Debug** iOS app. A native circular network button at the
bottom-right of the shared Compose content has the stable accessibility ID
`debug_network_inspector_open`. It opens the local SwiftUI inspector:

1. Run **successful HTTPS smoke** (`https://example.com/`) or **controlled
   local failure** (`https://127.0.0.1:1/den-77-controlled-failure`). These are
   temporary native `URLSession` verification requests, configured with the
   inspector protocol explicitly in that session, not BFF/provider calls.
2. Open a captured row to see method, redacted URL, status/error, duration,
   headers, and supported request/response bodies.
3. Tap **Clear** to erase the in-memory history immediately.

Before Koin bootstrap, the Debug-only startup path passes the `URLProtocol`
class to the narrow generic iOS adapter. Ktor Darwin applies it with
`configureSession { protocolClasses = … }` before `createTransitHttpClient()`
builds its `NSURLSession`. Each smoke action explicitly prepends and deduplicates
that same class in its own `URLSessionConfiguration`; global
`URLProtocol.registerClass` is not used. This directly wires the inspector into
the shared Ktor pipeline while making the temporary native verifier actions use
the same per-session mechanism, not production BFF traffic. The protocol
forwards each original request through a private
`URLSession` whose `protocolClasses` excludes the inspector, so it cannot recur
or introduce another network library. It retains method, headers, body, cache
policy, and timeout; cancellation, redirects, and authentication challenges use
the platform's normal handling.

## Retention, payloads, and privacy

Both hosts are strictly local to Debug builds. Nothing is sent to a remote
logger, analytics service, or cloud collector.

- Android's Chucker collector retains local transactions for one hour; it has
  a 256 KiB per-body limit but does not impose an exact entry count. iOS keeps
  at most 100 entries and evicts entries after one hour; it has no disk store.
- Android sends Chucker only redacted, bounded inspection copies before any
  Chucker storage occurs. Known-size, non-streaming request bodies at or below
  256 KiB are previewed; unknown-size, duplex, one-shot, streaming, and
  oversized requests become an omission marker before Chucker can buffer them.
  Responses use a non-consuming 256 KiB `peekBody` only when the declared
  length is known and within the limit and the type is supported text/JSON/form;
  their preview is redacted before it becomes Chucker's response body. Binary,
  XML, unknown-length, streaming, oversized, and unreadable responses become a
  small omission marker without exposing raw bytes to Chucker. iOS shows
  supported text/JSON/form bodies and omits binary, XML, streaming, and other
  unsupported bodies. Oversized supported bodies are marked truncated.
- Header names are matched case-insensitively. `Authorization`,
  `Proxy-Authorization`, `X-Api-Key`, `Api-Key`, `Cookie`, `Set-Cookie`,
  `X-Auth-Token`, `X-Access-Token`, and related credential headers are
  replaced before persistence/display. URLs remove user-info (`user:password@`)
  and redact credential-like query values. Supported JSON/form/plain-text
  bodies redact credential-like keys such as token, secret, password, API key,
  auth, credential, and session before storage. XML is omitted rather than
  parsed. The production BFF contract must continue to avoid secrets in URLs.

## Dependency and Release review

Android Debug uses `com.github.chuckerteam.chucker:library:4.3.1` only. It is
Apache-2.0, free for production use, actively maintained, and compatible with
the existing Ktor 3.3.3 OkHttp engine. Its resolved OkHttp 5.x graph is checked
by the Debug Android compile. No `library-no-op` artifact is used: Release has
no Chucker dependency or reference. iOS has no Pulse, Netfox, SPM inspector, or
other third-party inspection dependency; its inspector uses Foundation and
SwiftUI only.

Reproduce the absence checks before release:

```text
bash ./gradlew --no-daemon --no-build-cache :androidApp:assembleRelease
bash ./gradlew :androidApp:dependencies --configuration releaseRuntimeClasspath | rg -i chucker
# Expected: no output.

unzip -l androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk | rg -i chucker
# Expected: no output.

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -configuration Release -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/den77-release CODE_SIGNING_ALLOWED=NO build
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -showBuildSettings \
  | rg 'EXCLUDED_SOURCE_FILE_NAMES.*DebugNetworkInspector'
# Expected: DebugNetworkInspector.swift is excluded.
```

The Xcode project excludes `DebugNetworkInspector.swift` from Release
compilation/linking, and the only references in `GeorgiaTransitApp.swift` and
`ContentView.swift` are behind `#if DEBUG`. The shared iOS URL-protocol seam is
generic and inert in Release: it has no inspector class, initialization, UI, or
store reference until the Debug host supplies a class. Inspect the built
artifacts as an additional check:

```text
strings shared/build/xcode-frameworks/Release/iphonesimulator*/Shared.framework/Shared \
  | rg 'DebugNetworkInspector|debug_network_inspector'
# Expected: no output.

strings build/den77-release/Build/Products/Release-iphonesimulator/iosApp.app/iosApp \
  | rg 'DebugNetworkInspector|debug_network_inspector'
# Expected: no output.
```
