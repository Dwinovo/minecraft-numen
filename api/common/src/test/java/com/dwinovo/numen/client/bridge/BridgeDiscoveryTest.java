package com.dwinovo.numen.client.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BridgeDiscoveryTest {

    @TempDir
    Path home;

    @Test
    void readsVersionedLoopbackDiscoveryFile() throws Exception {
        write("""
                {"api_version":1,"base_url":"http://127.0.0.1:38471","token":"local-token"}
                """);

        BridgeDiscovery.Info info = BridgeDiscovery.load(home);

        assertEquals("http://127.0.0.1:38471", info.baseUrl().toString());
        assertEquals("local-token", info.token());
        assertEquals("ws://127.0.0.1:38471/v1/audio/capture", info.captureUri().toString());
    }

    @Test
    void rejectsWrongVersionRemoteHostsAndEmptyTokens() throws Exception {
        write("""
                {"api_version":2,"base_url":"http://127.0.0.1:38471","token":"x"}
                """);
        assertThrows(IllegalArgumentException.class, () -> BridgeDiscovery.load(home));

        write("""
                {"api_version":1,"base_url":"http://192.168.1.10:38471","token":"x"}
                """);
        assertThrows(IllegalArgumentException.class, () -> BridgeDiscovery.load(home));

        write("""
                {"api_version":1,"base_url":"http://127.0.0.1:38471","token":" "}
                """);
        assertThrows(IllegalArgumentException.class, () -> BridgeDiscovery.load(home));
    }

    private void write(String json) throws Exception {
        Path file = home.resolve("Library/Application Support/Numen Bridge/bridge.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }
}

