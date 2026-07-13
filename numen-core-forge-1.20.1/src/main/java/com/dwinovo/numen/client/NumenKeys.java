package com.dwinovo.numen.client;

import com.dwinovo.numen.client.screen.NumenScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Shared key mappings: defined once here, registered by each loader's client
 * init (Fabric {@code KeyMappingHelper} / NeoForge {@code RegisterKeyMappingsEvent}),
 * polled once per client tick via {@link #tick()}.
 */
public final class NumenKeys {

    /** G — open the companion roster panel (or straight into chat with a single pet). */
    public static final KeyMapping OPEN_ROSTER = new KeyMapping(
            com.dwinovo.numen.data.ModLanguageData.Keys.KEY_OPEN_ROSTER,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
            "key.categories.misc");

    private NumenKeys() {}

    /** Per-client-tick poll; key presses only register while no screen is open. */
    public static void tick() {
        while (OPEN_ROSTER.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.screen == null) {
                NumenScreen.openWorkspace();
            }
        }
    }
}
