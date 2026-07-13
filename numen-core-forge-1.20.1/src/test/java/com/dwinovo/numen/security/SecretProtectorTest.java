package com.dwinovo.numen.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SecretProtectorTest {
    @TempDir Path directory;

    @Test void aesFallbackRoundTripsWithoutPersistingPlaintext() throws Exception {
        SecretProtector protector = new SecretProtector.AesGcm(directory.resolve("secret.key"));
        String fakeKey = "sk-test-only-not-a-real-key";
        String protectedValue = protector.protect(fakeKey);
        assertNotEquals(fakeKey, protectedValue);
        assertFalse(protectedValue.contains(fakeKey));
        assertEquals(fakeKey, protector.unprotect(protectedValue));
        assertFalse(new String(Files.readAllBytes(directory.resolve("secret.key"))).contains(fakeKey));
    }
}
