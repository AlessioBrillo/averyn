@preconcurrency import AppAuth
import Foundation
import UIKit

enum AuthError: Error {
    case noServer
    case badServerResponse
    case cancelled
    case noToken
}

/// The server this app syncs to, and the OIDC session with that server's identity provider (ADR-0011): the app
/// is given only the server URL; the issuer and client id come from `GET /v1/client-config`. Authorization code
/// with PKCE in the system browser (AppAuth, RFC 8252); the session lives in the Keychain (`KeychainStore`).
@MainActor
final class AuthManager: ObservableObject {
    /// Must match the redirect URI registered with the identity provider (infrastructure/compose/idp-init.sh).
    private static let redirectURL = URL(string: "dev.averyn.app:/oauth2redirect")!
    private static let serverKey = "serverURL"

    @Published var serverURL: String {
        didSet { UserDefaults.standard.set(serverURL, forKey: Self.serverKey) }
    }

    @Published private(set) var isSignedIn: Bool

    private var authState: OIDAuthState?
    private var flow: OIDExternalUserAgentSession?

    init() {
        serverURL = UserDefaults.standard.string(forKey: Self.serverKey) ?? ""
        let state = KeychainStore.loadAuthState()
        authState = state
        isSignedIn = state?.isAuthorized ?? false
    }

    /// The server URL as typed, trimmed and without trailing slashes; nil if empty.
    var normalizedServerURL: String? {
        var url = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while url.hasSuffix("/") { url.removeLast() }
        return url.isEmpty ? nil : url
    }

    /// Opens the system browser at the IdP's login page and stores the session. Throws if the server cannot be
    /// reached or the user cancels.
    func signIn() async throws {
        guard let server = normalizedServerURL else { throw AuthError.noServer }
        let config = try await fetchClientConfig(server: server)
        let issuer = config.issuer.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let discoveryURL = URL(string: issuer + "/.well-known/openid-configuration") else {
            throw AuthError.badServerResponse
        }
        let configuration = try await discover(discoveryURL)
        let request = OIDAuthorizationRequest(
            configuration: configuration,
            clientId: config.clientId,
            // offline_access: a refresh token, so background sync keeps working
            scopes: ["openid", "offline_access"],
            redirectURL: Self.redirectURL,
            responseType: OIDResponseTypeCode,
            additionalParameters: nil
        )
        guard let presenter = Self.topViewController() else { throw AuthError.cancelled }
        let state: OIDAuthState = try await withCheckedThrowingContinuation { continuation in
            flow = OIDAuthState.authState(byPresenting: request, presenting: presenter) { state, error in
                if let state {
                    continuation.resume(returning: state)
                } else {
                    continuation.resume(throwing: error ?? AuthError.cancelled)
                }
            }
        }
        flow = nil
        save(state)
    }

    /// An access token that is valid now (refreshing it if needed), or nil if the user is not signed in or the IdP
    /// refused the refresh token (then the session is dropped: sign in again). Throws on transient trouble such as
    /// no network: the caller retries later.
    func freshAccessToken() async throws -> String? {
        guard let state = authState else { return nil }
        do {
            let token: String = try await withCheckedThrowingContinuation { continuation in
                state.performAction(freshTokens: { accessToken, _, error in
                    if let accessToken {
                        continuation.resume(returning: accessToken)
                    } else {
                        continuation.resume(throwing: error ?? AuthError.noToken)
                    }
                })
            }
            save(state) // a refresh changes the stored tokens
            return token
        } catch let error as NSError where error.domain == OIDOAuthTokenErrorDomain {
            signOut()
            return nil
        }
    }

    /// Forgets the session on this device. (The IdP session in the browser is not ended: add end_session later.)
    func signOut() {
        authState = nil
        isSignedIn = false
        KeychainStore.deleteAuthState()
    }

    private func save(_ state: OIDAuthState) {
        authState = state
        isSignedIn = state.isAuthorized
        KeychainStore.saveAuthState(state)
    }

    private struct ClientConfig: Decodable {
        let issuer: String
        let clientId: String
    }

    /// `GET /v1/client-config` (public): the issuer and the public client id apps sign in with.
    private func fetchClientConfig(server: String) async throws -> ClientConfig {
        guard let url = URL(string: server + "/v1/client-config") else { throw AuthError.noServer }
        let (data, response) = try await URLSession.shared.data(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw AuthError.badServerResponse }
        return try JSONDecoder().decode(ClientConfig.self, from: data)
    }

    private func discover(_ url: URL) async throws -> OIDServiceConfiguration {
        try await withCheckedThrowingContinuation { continuation in
            OIDAuthorizationService.discoverConfiguration(forDiscoveryURL: url) { configuration, error in
                if let configuration {
                    continuation.resume(returning: configuration)
                } else {
                    continuation.resume(throwing: error ?? AuthError.badServerResponse)
                }
            }
        }
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
