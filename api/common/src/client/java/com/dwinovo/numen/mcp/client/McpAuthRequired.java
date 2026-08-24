package com.dwinovo.numen.mcp.client;

/**
 * Thrown by the HTTP transport when the server answers {@code 401 Unauthorized} — the MCP endpoint
 * is OAuth-protected and no valid access token is present. Carries the {@code WWW-Authenticate}
 * header (which per RFC 9728 points at the protected-resource-metadata URL) so {@link McpOAuth}
 * can discover the authorization server and run the OAuth 2.1 flow.
 */
final class McpAuthRequired extends RuntimeException {

    /** Raw {@code WWW-Authenticate} header value, or empty if the server sent none. */
    private final String wwwAuthenticate;

    McpAuthRequired(String wwwAuthenticate) {
        super("MCP server requires authorization (HTTP 401)");
        this.wwwAuthenticate = wwwAuthenticate == null ? "" : wwwAuthenticate;
    }

    String wwwAuthenticate() {
        return wwwAuthenticate;
    }

    /**
     * Extract the {@code resource_metadata="…"} URL from the WWW-Authenticate header, or {@code null}
     * if absent (the caller then falls back to {@code <serverOrigin>/.well-known/oauth-protected-resource}).
     */
    String resourceMetadataUrl() {
        String key = "resource_metadata=";
        int i = wwwAuthenticate.indexOf(key);
        if (i < 0) return null;
        String rest = wwwAuthenticate.substring(i + key.length()).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end > 1 ? rest.substring(1, end) : null;
        }
        int end = rest.indexOf(',');
        return (end > 0 ? rest.substring(0, end) : rest).trim();
    }
}
