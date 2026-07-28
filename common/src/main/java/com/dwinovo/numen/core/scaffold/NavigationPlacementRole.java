package com.dwinovo.numen.core.scaffold;

/** Semantic purpose supplied by the navigation movement that places a block. */
public enum NavigationPlacementRole {
    PILLAR(true),
    BRIDGE(false),
    STEP(false),
    ROUTE(false);

    private final boolean automaticallyReclaimable;

    NavigationPlacementRole(boolean automaticallyReclaimable) {
        this.automaticallyReclaimable = automaticallyReclaimable;
    }

    public boolean automaticallyReclaimable() {
        return automaticallyReclaimable;
    }

    public String preservationReason() {
        return "preserved_navigation_" + name().toLowerCase(java.util.Locale.ROOT);
    }
}
