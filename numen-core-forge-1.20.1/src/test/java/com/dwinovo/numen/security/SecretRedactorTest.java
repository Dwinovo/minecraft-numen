package com.dwinovo.numen.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {
    @Test void redactsHeadersJsonQueriesCredentialsAndKnownSecret() {
        String secret = "test-secret-123456";
        String input = "Authorization: Bearer " + secret + "\nProxy-Authorization: Basic abc\n"
                + "Cookie: session=abc\nSet-Cookie: auth=xyz\n"
                + "{\"api_key\":\"" + secret + "\",\"password\":\"pw\"}\n"
                + "https://user:pass@example.test/path?token=abc&signature=def";
        String clean = SecretRedactor.redact(input, secret);
        assertFalse(clean.contains(secret));
        assertFalse(clean.contains("Basic abc"));
        assertFalse(clean.contains("session=abc"));
        assertFalse(clean.contains("user:pass"));
        assertFalse(clean.contains("token=abc"));
        assertFalse(clean.contains("signature=def"));
        assertTrue(clean.contains("<redacted>"));
    }
}
