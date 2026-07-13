package com.dwinovo.numen.inventory;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.function.BiConsumer;

/** Optional core hook used to suspend body tasks while the owner edits the same inventory. */
public final class CompanionInventoryAccess {
    private static BiConsumer<NumenPlayer, Boolean> handler = (body, opened) -> { };

    private CompanionInventoryAccess() { }

    public static void installHandler(BiConsumer<NumenPlayer, Boolean> value) {
        handler = value == null ? (body, opened) -> { } : value;
    }

    public static void changed(NumenPlayer body, boolean opened) {
        if (body != null) handler.accept(body, opened);
    }
}
