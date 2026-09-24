package com.dwinovo.numen.client.api;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** A client-side page contributed to the companion's status area. */
public interface NumenStatusPage {

    /** Stable namespaced page id, such as {@code examplemod:character_card}. */
    String id();

    /** Label rendered as a status-page tab. */
    Component title();

    /** Called when this page becomes active for a companion. */
    default void onSelected(UUID companion) {}

    /** Called when the active world changes, so a page can drop its client cache. */
    default void onWorldChanged(String worldId) {}

    /** Draw inside the supplied content bounds. */
    void render(GuiGraphics graphics, Font font, UUID companion,
                int x, int y, int width, int height, int mouseX, int mouseY);

    /** Return true when the page consumes this click. */
    default boolean mouseClicked(UUID companion, double mouseX, double mouseY, int button) {
        return false;
    }

    /** Return true when the page consumes this scroll input. */
    default boolean mouseScrolled(UUID companion, double mouseX, double mouseY, double deltaY) {
        return false;
    }

    /** Called periodically while this page is selected. */
    default void tick(UUID companion) {}
}
