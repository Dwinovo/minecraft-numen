package com.dwinovo.numen.client.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Client registry for status pages contributed by addons. */
public final class NumenStatusPages {

    private static final List<NumenStatusPage> PAGES = new ArrayList<>();

    private NumenStatusPages() {}

    public static synchronized void register(NumenStatusPage page) {
        Objects.requireNonNull(page, "page");
        if (page.id() == null || !page.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Status page id must be namespaced: " + page.id());
        }
        if (PAGES.stream().anyMatch(existing -> existing.id().equals(page.id()))) {
            throw new IllegalStateException("Duplicate Numen status page: " + page.id());
        }
        PAGES.add(page);
    }

    public static synchronized List<NumenStatusPage> all() {
        return List.copyOf(PAGES);
    }

    public static void worldChanged(String worldId) {
        for (NumenStatusPage page : all()) page.onWorldChanged(worldId);
    }
}
