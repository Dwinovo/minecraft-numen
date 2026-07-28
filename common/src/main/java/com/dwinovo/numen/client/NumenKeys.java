package com.dwinovo.numen.client;

import com.dwinovo.numen.client.chat.CompanionChatScreen;
import com.dwinovo.numen.client.screen.NumenScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.lwjgl.glfw.GLFW;

/**
 * Shared key mappings: defined once here, registered by each loader's client
 * init (Fabric {@code KeyMappingHelper} / NeoForge {@code RegisterKeyMappingsEvent}),
 * polled once per client tick via {@link #tick()}.
 */
public final class NumenKeys {

    /** Dedicated vanilla Controls category so the binding is easy to find and rebind (Options → Controls → Numen). */
    public static final String CATEGORY = com.dwinovo.numen.data.ModLanguageData.Keys.KEY_CATEGORY_NUMEN;

    /** G — open the companion roster panel (or straight into chat with a single pet). */
    public static final KeyMapping OPEN_ROSTER = new KeyMapping(
            com.dwinovo.numen.data.ModLanguageData.Keys.KEY_OPEN_ROSTER,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
            CATEGORY);

    /** V — 面对面搭话:准星指着自己的同伴按下,弹极简输入框。可改键,
     *  准星提示({@code TalkHint})会跟着显示当前绑定。 */
    public static final KeyMapping TALK_COMPANION = new KeyMapping(
            com.dwinovo.numen.data.ModLanguageData.Keys.KEY_TALK_COMPANION,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V,
            CATEGORY);

    private NumenKeys() {}

    /** Per-client-tick poll; key presses only register while no screen is open. */
    public static void tick() {
        while (OPEN_ROSTER.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.screen == null) {
                NumenScreen.openWorkspace();
            }
        }
        while (TALK_COMPANION.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) {
                continue;
            }
            AbstractClientPlayer body = CompanionChatScreen.crosshairCompanion();
            if (body == null) {
                continue;   // 没指着自己的同伴:这个键不做别的事
            }
            String name = com.dwinovo.numen.client.agent.NumenRoster.instance().name(body.getUUID());
            if (name == null || name.isBlank()) {
                name = body.getScoreboardName();
            }
            mc.setScreen(new CompanionChatScreen(body.getUUID(), name));
        }
    }
}
