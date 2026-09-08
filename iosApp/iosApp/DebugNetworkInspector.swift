#if DEBUG
import Foundation
import Shared
import SwiftUI

private let networkInspectorHandledKey = "com.denis.georgiatransit.debugNetworkInspectorHandled"
private let networkInspectorMaxBodyBytes = 256 * 1024
private let networkInspectorHistoryLimit = 100
private let networkInspectorHistoryLifetime: TimeInterval = 60 * 60

enum DebugNetworkInspector {
    private static let installationLock = NSLock()
    private static var isInstalled = false

    static func install() {
        installationLock.lock()
        defer { installationLock.unlock() }

        guard !isInstalled else { return }
        TransitHttpClient_iosKt.installTransitUrlProtocolClass(protocolClass: NetworkInspectorURLProtocol.self)
        isInstalled = true
    }
}

private func prependNetworkInspectorProtocol(to configuration: URLSessionConfiguration) {
    let inspectorProtocolClass: AnyClass = NetworkInspectorURLProtocol.self
    let configuredClasses = configuration.protocolClasses ?? []
    configuration.protocolClasses =
        [inspectorProtocolClass] + configuredClasses.filter { $0 != inspectorProtocolClass }
}

private func removeNetworkInspectorProtocol(from configuration: URLSessionConfiguration) {
    let inspectorProtocolClass: AnyClass = NetworkInspectorURLProtocol.self
    configuration.protocolClasses =
        (configuration.protocolClasses ?? []).filter { $0 != inspectorProtocolClass }
}

private enum NetworkInspectorHistoryBarrier {
    private static let lock = NSLock()
    private static var generation: UInt64 = 0

    static func captureGeneration() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return generation
    }

    static func permits(_ capturedGeneration: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return generation == capturedGeneration
    }

    static func clear() {
        lock.lock()
        generation &+= 1
        lock.unlock()
    }
}

private final class NetworkInspectorURLProtocol: URLProtocol, URLSessionDataDelegate, URLSessionTaskDelegate {
    private let stateLock = NSRecursiveLock()
    private var session: URLSession?
    private var forwardingTask: URLSessionDataTask?
    private var response: URLResponse?
    private var responseBody = Data()
    private var responseWasTruncated = false
    private var startedAt = Date()
    private var captureGeneration = NetworkInspectorHistoryBarrier.captureGeneration()
    private var isFinished = false

    override class func canInit(with request: URLRequest) -> Bool {
        guard let scheme = request.url?.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
            return false
        }
        return URLProtocol.property(forKey: networkInspectorHandledKey, in: request) == nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        stateLock.lock()
        guard !isFinished else {
            stateLock.unlock()
            return
        }
        startedAt = Date()
        captureGeneration = NetworkInspectorHistoryBarrier.captureGeneration()
        stateLock.unlock()

        guard let forwardedRequest = (request as NSURLRequest).mutableCopy() as? NSMutableURLRequest else {
            finish(with: URLError(.badURL))
            return
        }
        URLProtocol.setProperty(true, forKey: networkInspectorHandledKey, in: forwardedRequest)

        let configuration = URLSessionConfiguration.default
        removeNetworkInspectorProtocol(from: configuration)
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        let forwardingTask = session.dataTask(with: forwardedRequest as URLRequest)

        stateLock.lock()
        guard !isFinished else {
            stateLock.unlock()
            forwardingTask.cancel()
            session.invalidateAndCancel()
            return
        }
        self.session = session
        self.forwardingTask = forwardingTask
        stateLock.unlock()
        forwardingTask.resume()
    }

    override func stopLoading() {
        let resources =
            finish(
                with: URLError(.cancelled),
                notifyClient: false,
                invalidateSession: false
            )
        resources?.task?.cancel()
        resources?.session?.invalidateAndCancel()
    }

    func urlSession(
        _: URLSession,
        dataTask _: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        stateLock.lock()
        guard !isFinished else {
            stateLock.unlock()
            completionHandler(.cancel)
            return
        }
        self.response = response
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        let disposition: URLSession.ResponseDisposition = isFinished ? .cancel : .allow
        stateLock.unlock()
        completionHandler(disposition)
    }

    func urlSession(_: URLSession, dataTask _: URLSessionDataTask, didReceive data: Data) {
        stateLock.lock()
        guard !isFinished else {
            stateLock.unlock()
            return
        }
        appendResponseBodyLocked(data)
        client?.urlProtocol(self, didLoad: data)
        stateLock.unlock()
    }

    func urlSession(
        _: URLSession,
        task _: URLSessionTask,
        willPerformHTTPRedirection _: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(isActive ? request : nil)
    }

    func urlSession(
        _: URLSession,
        task _: URLSessionTask,
        didReceive _: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        completionHandler(isActive ? .performDefaultHandling : .cancelAuthenticationChallenge, nil)
    }

    func urlSession(_: URLSession, task _: URLSessionTask, didCompleteWithError error: Error?) {
        finish(with: error)
    }

    private var isActive: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return !isFinished
    }

    private func appendResponseBodyLocked(_ data: Data) {
        let remainingBytes = networkInspectorMaxBodyBytes - responseBody.count
        guard remainingBytes > 0 else {
            responseWasTruncated = true
            return
        }

        let capturedData = Data(data.prefix(remainingBytes))
        responseBody.append(capturedData)
        if capturedData.count < data.count {
            responseWasTruncated = true
        }
    }

    private struct ForwardingResources {
        let task: URLSessionDataTask?
        let session: URLSession?
    }

    @discardableResult
    private func finish(
        with error: Error?,
        notifyClient: Bool = true,
        invalidateSession: Bool = true
    ) -> ForwardingResources? {
        stateLock.lock()
        guard !isFinished else {
            stateLock.unlock()
            return nil
        }
        isFinished = true
        let finalResponse = response
        let finalResponseBody = responseBody
        let finalResponseWasTruncated = responseWasTruncated
        let finalDuration = Date().timeIntervalSince(startedAt)
        let finalGeneration = captureGeneration
        let finalTask = forwardingTask
        let finalSession = session
        stateLock.unlock()

        let capture = NetworkInspectorCapture(
            request: request,
            response: finalResponse,
            responseBody: finalResponseBody,
            responseWasTruncated: finalResponseWasTruncated,
            error: error,
            duration: finalDuration
        )
        Task { @MainActor in
            NetworkInspectorStore.shared.append(capture.entry, generation: finalGeneration)
        }

        if notifyClient {
            if let error {
                client?.urlProtocol(self, didFailWithError: error)
            } else {
                client?.urlProtocolDidFinishLoading(self)
            }
        }
        if invalidateSession {
            finalSession?.finishTasksAndInvalidate()
        }
        return ForwardingResources(task: finalTask, session: finalSession)
    }
}

@MainActor
private final class NetworkInspectorStore: ObservableObject {
    static let shared = NetworkInspectorStore()

    @Published private(set) var entries: [NetworkInspectorEntry] = []
    @Published private(set) var smokeStatus = "No smoke request has run yet."
    private var expiryTimer: Timer?

    private init() {
        expiryTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.discardExpiredEntries()
            }
        }
    }

    deinit {
        expiryTimer?.invalidate()
    }

    func append(_ entry: NetworkInspectorEntry, generation: UInt64) {
        guard NetworkInspectorHistoryBarrier.permits(generation) else { return }
        discardExpiredEntries()
        entries.insert(entry, at: 0)
        if entries.count > networkInspectorHistoryLimit {
            entries.removeLast(entries.count - networkInspectorHistoryLimit)
        }
    }

    func clear() {
        NetworkInspectorHistoryBarrier.clear()
        entries.removeAll()
    }

    func runSuccessfulSmoke() {
        runSmoke(successful: true)
    }

    func runControlledFailure() {
        runSmoke(successful: false)
    }

    private func runSmoke(successful: Bool) {
        let label = successful ? "Successful HTTPS smoke" : "Controlled local failure"
        smokeStatus = "\(label) is running…"
        let url =
            URL(
                string: successful
                    ? "https://example.com/"
                    : "https://127.0.0.1:1/den-77-controlled-failure"
            )!
        let configuration = URLSessionConfiguration.default
        prependNetworkInspectorProtocol(to: configuration)
        let session = URLSession(configuration: configuration)
        session.dataTask(with: url) { _, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode
            Task { @MainActor in
                if let status {
                    self.smokeStatus = "\(label) completed with HTTP \(status)."
                } else if let error {
                    self.smokeStatus = "\(label) recorded \(type(of: error))."
                } else {
                    self.smokeStatus = "\(label) finished without an HTTP response."
                }
            }
            session.finishTasksAndInvalidate()
        }.resume()
    }

    private func discardExpiredEntries() {
        let expiration = Date().addingTimeInterval(-networkInspectorHistoryLifetime)
        entries.removeAll { $0.finishedAt < expiration }
    }
}

private struct NetworkInspectorEntry: Identifiable {
    let id = UUID()
    let method: String
    let url: String
    let statusCode: Int?
    let error: String?
    let duration: TimeInterval
    let requestHeaders: [String: String]
    let responseHeaders: [String: String]
    let requestBody: NetworkInspectorBody
    let responseBody: NetworkInspectorBody
    let finishedAt: Date

    var resultText: String {
        if let statusCode { return "HTTP \(statusCode)" }
        if let error { return error }
        return "No response"
    }
}

private struct NetworkInspectorBody {
    let text: String
    let isOmitted: Bool
    let isTruncated: Bool
}

private struct NetworkInspectorCapture {
    let entry: NetworkInspectorEntry

    init(
        request: URLRequest,
        response: URLResponse?,
        responseBody: Data,
        responseWasTruncated: Bool,
        error: Error?,
        duration: TimeInterval
    ) {
        let responseHeaders = (response as? HTTPURLResponse)?.allHeaderFields ?? [:]
        let requestContentType = NetworkInspectorSanitizer.header(named: "Content-Type", in: request.allHTTPHeaderFields ?? [:])
        let responseContentType =
            NetworkInspectorSanitizer.header(named: "Content-Type", in: responseHeaders) ?? response?.mimeType

        entry = NetworkInspectorEntry(
            method: request.httpMethod ?? "GET",
            url: NetworkInspectorSanitizer.redactedURL(request.url),
            statusCode: (response as? HTTPURLResponse)?.statusCode,
            error: error.map { NetworkInspectorSanitizer.redactText($0.localizedDescription) },
            duration: duration,
            requestHeaders: NetworkInspectorSanitizer.redactedHeaders(request.allHTTPHeaderFields ?? [:]),
            responseHeaders: NetworkInspectorSanitizer.redactedHeaders(responseHeaders),
            requestBody: NetworkInspectorSanitizer.body(
                data: request.httpBody,
                contentType: requestContentType,
                isStream: request.httpBodyStream != nil,
                wasTruncated: (request.httpBody?.count ?? 0) > networkInspectorMaxBodyBytes
            ),
            responseBody: NetworkInspectorSanitizer.body(
                data: responseBody,
                contentType: responseContentType,
                isStream: false,
                wasTruncated: responseWasTruncated
            ),
            finishedAt: Date()
        )
    }
}

private enum NetworkInspectorSanitizer {
    private static let redactedValue = "[REDACTED]"
    private static let exactCredentialNames: Set<String> = [
        "authorization",
        "proxyauthorization",
        "xapikey",
        "apikey",
        "cookie",
        "setcookie",
        "xauthtoken",
        "xaccesstoken",
        "accesstoken",
        "refreshtoken",
        "idtoken",
    ]
    // Coordinate fields sent to BFF location endpoints. Keep values out of local Debug history.
    private static let exactLocationNames: Set<String> = [
        "lat", "lon", "fromlat", "fromlon", "tolat", "tolon", "latitude", "longitude",
    ]
    private static let credentialHeaders: Set<String> = [
        "authorization",
        "proxy-authorization",
        "x-api-key",
        "api-key",
        "cookie",
        "set-cookie",
        "x-auth-token",
        "x-access-token",
        "x-api-token",
        "x-session-token",
        "x-secret",
        "x-password",
        "x-credential",
        "x-access-key",
        "x-bearer-token",
        "token",
        "secret",
        "password",
        "credential",
        "session",
        "www-authenticate",
        "location",
        "content-location",
        "referer",
    ]

    static func redactedURL(_ url: URL?) -> String {
        guard let url else { return "Invalid URL" }
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return "[Unparseable URL omitted]"
        }
        components.user = nil
        components.password = nil
        components.queryItems = components.queryItems?.map { item in
            guard isSensitiveName(item.name) else { return item }
            var redacted = item
            redacted.value = redactedValue
            return redacted
        }
        return components.string ?? "[Unparseable URL omitted]"
    }

    static func redactedHeaders(_ headers: [AnyHashable: Any]) -> [String: String] {
        Dictionary(uniqueKeysWithValues: headers.map { key, value in
            let name = String(describing: key)
            let visibleValue = credentialHeaders.contains(name.lowercased()) || isCredentialName(name)
                ? redactedValue
                : redactText(String(describing: value))
            return (name, visibleValue)
        })
    }

    static func header(named name: String, in headers: [AnyHashable: Any]) -> String? {
        headers.first { String(describing: $0.key).caseInsensitiveCompare(name) == .orderedSame }
            .map { String(describing: $0.value) }
    }

    static func body(
        data: Data?,
        contentType: String?,
        isStream: Bool,
        wasTruncated: Bool
    ) -> NetworkInspectorBody {
        if isStream {
            return NetworkInspectorBody(text: "[Streaming request body omitted]", isOmitted: true, isTruncated: false)
        }
        guard let data else {
            return NetworkInspectorBody(text: "[No body]", isOmitted: false, isTruncated: false)
        }

        let capturedData = Data(data.prefix(networkInspectorMaxBodyBytes))
        let truncated = wasTruncated || capturedData.count < data.count
        let mediaType = contentType?.lowercased() ?? ""
        guard isSupportedText(mediaType), let text = String(data: capturedData, encoding: .utf8) else {
            let suffix = truncated ? " after 256 KiB cap" : ""
            return NetworkInspectorBody(
                text: "[Binary or unsupported body omitted\(suffix)]",
                isOmitted: true,
                isTruncated: truncated
            )
        }

        let redactedText = mediaType.contains("json") ? redactJSON(text) : redactText(text)
        let suffix = truncated ? "\n[Body truncated after 256 KiB]" : ""
        return NetworkInspectorBody(text: redactedText + suffix, isOmitted: false, isTruncated: truncated)
    }

    static func redactText(_ text: String) -> String {
        let userInfoRedacted = replacing(
            text,
            pattern: "(?i)(https?://)(?:[^/@\\s]+@)",
            replacement: "$1\(redactedValue)@"
        )
        let queryRedacted = replacing(
            userInfoRedacted,
            pattern: "(?i)([?&](?:[^=&?#]*?(?:token|secret|password|credential|api[_-]?key|auth|session)[^=&?#]*)=)[^&#\\s]+"
        )
        let locationQueryRedacted = replacing(
            queryRedacted,
            pattern: "(?i)([?&](?:lat|lon|fromlat|fromlon|tolat|tolon|latitude|longitude)=)[^&#\\s]+"
        )
        let credentialKeyRedacted = replacing(
            locationQueryRedacted,
            pattern: "(?i)(\\b(?:authorization|proxy-authorization|x-api-key|api-key|cookie|set-cookie|x-auth-token|x-access-token|access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|password|secret|credential|session(?:[_-]?id)?)\\b\\s*[:=]\\s*)(?:\\\"(?:\\\\.|[^\\\"])*\\\"|'(?:\\\\.|[^'])*'|[^,\\s&;}\\]]+)"
        )
        return replacing(
            credentialKeyRedacted,
            pattern: "(?i)(\\b(?:lat|lon|fromlat|fromlon|tolat|tolon|latitude|longitude)\\b\\s*[:=]\\s*)(?:\\\"(?:\\\\.|[^\\\"])*\\\"|'(?:\\\\.|[^'])*'|[^,\\s&;}\\]]+)"
        )
    }

    private static func redactJSON(_ text: String) -> String {
        guard
            let data = text.data(using: .utf8),
            let value = try? JSONSerialization.jsonObject(with: data),
            JSONSerialization.isValidJSONObject(value),
            let redactedData = try? JSONSerialization.data(
                withJSONObject: redactJSONValue(value),
                options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
            ),
            let redacted = String(data: redactedData, encoding: .utf8)
        else {
            return redactText(text)
        }
        return redacted
    }

    private static func redactJSONValue(_ value: Any) -> Any {
        if let object = value as? [String: Any] {
            return Dictionary(uniqueKeysWithValues: object.map { key, value in
                (key, isSensitiveName(key) ? redactedValue : redactJSONValue(value))
            })
        }
        if let array = value as? [Any] {
            return array.map(redactJSONValue)
        }
        return value
    }

    private static func replacing(_ text: String, pattern: String, replacement: String = "$1\(redactedValue)") -> String {
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return text }
        let range = NSRange(text.startIndex..., in: text)
        return expression.stringByReplacingMatches(in: text, range: range, withTemplate: replacement)
    }

    private static func isCredentialName(_ name: String) -> Bool {
        let normalized = name.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted).joined()
        return exactCredentialNames.contains(normalized) ||
            ["token", "secret", "password", "credential", "apikey", "auth", "session"].contains {
                normalized.contains($0)
            }
    }

    private static func isSensitiveName(_ name: String) -> Bool {
        let normalized = name.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted).joined()
        return isCredentialName(name) || exactLocationNames.contains(normalized)
    }

    private static func isSupportedText(_ mediaType: String) -> Bool {
        !mediaType.contains("xml") &&
            (mediaType.hasPrefix("text/") ||
                mediaType.contains("json") ||
                mediaType.contains("x-www-form-urlencoded"))
    }
}

struct DebugNetworkInspectorOverlay: View {
    @State private var isPresented = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            ComposeViewController()
            Button {
                isPresented = true
            } label: {
                Image(systemName: "network")
                    .font(.title2)
                    .padding(14)
            }
            .accessibilityIdentifier("debug_network_inspector_open")
            .accessibilityLabel("Open debug network inspector")
            .background(.ultraThinMaterial, in: Circle())
            .padding()
        }
        .sheet(isPresented: $isPresented) {
            DebugNetworkInspectorView()
        }
    }
}

private struct DebugNetworkInspectorView: View {
    @ObservedObject private var store = NetworkInspectorStore.shared

    var body: some View {
        NavigationStack {
            List {
                Section("Debug smoke") {
                    Button("Run successful HTTPS smoke") {
                        store.runSuccessfulSmoke()
                    }
                    .accessibilityIdentifier("debug_network_inspector_run_success")

                    Button("Run controlled local failure") {
                        store.runControlledFailure()
                    }
                    .accessibilityIdentifier("debug_network_inspector_run_failure")

                    Text(store.smokeStatus)
                        .accessibilityIdentifier("debug_network_inspector_result")
                }

                Section("Captured requests") {
                    if store.entries.isEmpty {
                        Text("No local requests captured.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(store.entries) { entry in
                        NavigationLink {
                            DebugNetworkInspectorDetail(entry: entry)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("\(entry.method) \(entry.url)")
                                    .lineLimit(2)
                                Text("\(entry.resultText) · \(Int(entry.duration * 1000)) ms")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Network inspector")
            .toolbar {
                Button("Clear") {
                    store.clear()
                }
                .accessibilityIdentifier("debug_network_inspector_clear")
            }
        }
    }
}

private struct DebugNetworkInspectorDetail: View {
    let entry: NetworkInspectorEntry

    var body: some View {
        List {
            Section("Summary") {
                LabeledContent("Method", value: entry.method)
                LabeledContent("URL", value: entry.url)
                LabeledContent("Result", value: entry.resultText)
                LabeledContent("Duration", value: "\(Int(entry.duration * 1000)) ms")
            }
            Section("Request headers") {
                HeaderList(headers: entry.requestHeaders)
            }
            Section("Request body") {
                Text(entry.requestBody.text)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
            }
            Section("Response headers") {
                HeaderList(headers: entry.responseHeaders)
            }
            Section("Response body") {
                Text(entry.responseBody.text)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
            }
        }
        .navigationTitle("Request details")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct HeaderList: View {
    let headers: [String: String]

    var body: some View {
        if headers.isEmpty {
            Text("No headers")
                .foregroundStyle(.secondary)
        } else {
            ForEach(headers.keys.sorted(), id: \.self) { name in
                LabeledContent(name, value: headers[name] ?? "")
            }
        }
    }
}
#endif
