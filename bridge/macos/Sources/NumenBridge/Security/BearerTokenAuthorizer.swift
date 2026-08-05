struct BearerTokenAuthorizer: Sendable {
    let token: String

    func allows(headerValue: String?) -> Bool {
        headerValue == "Bearer \(token)"
    }
}
