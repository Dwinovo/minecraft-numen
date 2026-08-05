package com.dwinovo.numen.client.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the authenticated loopback endpoint published by Numen Bridge.app. */
public final class BridgeDiscovery {

    private BridgeDiscovery() {}

    public static Info load() {
        return load(Path.of(System.getProperty("user.home")));
    }

    public static Info load(Path home) {
        Path file = home.resolve("Library/Application Support/Numen Bridge/bridge.json");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!root.has("api_version") || root.get("api_version").getAsInt() != 1) {
                throw new IllegalArgumentException("Unsupported Numen Bridge API version");
            }
            URI base = URI.create(root.get("base_url").getAsString());
            String token = root.get("token").getAsString();
            return new Info(base, token);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException("Numen Bridge discovery file is unavailable", e);
        }
    }

    public record Info(URI baseUrl, String token) {
        public Info {
            if (baseUrl == null || !"http".equalsIgnoreCase(baseUrl.getScheme())
                    || !"127.0.0.1".equals(baseUrl.getHost())
                    || baseUrl.getPort() < 1 || baseUrl.getPort() > 65_535) {
                throw new IllegalArgumentException("Numen Bridge must use an HTTP loopback URL");
            }
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("Numen Bridge token is empty");
            }
            baseUrl = URI.create(trimSlash(baseUrl.toString()));
            token = token.trim();
        }

        public URI captureUri() {
            return URI.create(webSocketBase() + "/v1/audio/capture");
        }

        public URI speechUri() {
            return URI.create(baseUrl + "/v1/audio/speech");
        }

        private String webSocketBase() {
            return "ws" + baseUrl.toString().substring("http".length());
        }

        private static String trimSlash(String value) {
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
    }
}
