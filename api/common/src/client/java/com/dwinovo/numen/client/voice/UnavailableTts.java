package com.dwinovo.numen.client.voice;

import java.util.concurrent.CompletableFuture;

/** Keeps a missing optional Bridge installation from crashing the client thread. */
final class UnavailableTts implements TtsBackend {
    private final Throwable cause;

    UnavailableTts(Throwable cause) {
        this.cause = cause;
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text) {
        return CompletableFuture.failedFuture(cause);
    }

    @Override
    public String describe() {
        return "unavailable";
    }
}
