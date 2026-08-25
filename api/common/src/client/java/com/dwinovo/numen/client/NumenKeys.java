package com.dwinovo.numen.client;

import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.CompanionChatScreen;
import com.dwinovo.numen.client.chat.CompanionWheelScreen;
import com.dwinovo.numen.client.chat.QuickVoice;
import com.dwinovo.numen.client.chat.SelectedCompanion;
import com.dwinovo.numen.client.screen.NumenScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Shared key mappings: defined once here, registered by each loader's client
 * init (Fabric {@code KeyMappingHelper} / NeoForge {@code RegisterKeyMappingsEvent}),
 * polled once per client tick via {@link #tick()}.
 *
 * <p>快捷交互三件套围绕「当前交互对象」({@link SelectedCompanion}):
 * 轮盘选人 → 对话键发文字 → 语音键按住说话。默认键位都避开原版占用,
 * Controls 的 Numen 区可随意改绑。
 */
public final class NumenKeys {

    /** Dedicated vanilla Controls category so the binding is easy to find and rebind (Options → Controls → Numen). */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("numen_api", "companions"));

    /**
     * N — open the companion roster panel (or straight into chat with a single pet).
     * 1.21.6+ vanilla binds Quick Actions to G by default, and vanilla's key lookup
     * is one-mapping-per-key (Fabric keeps it; only NeoForge patches in multi-dispatch),
     * so a G default would never receive clicks on Fabric.
     */
    public static final KeyMapping OPEN_ROSTER = new KeyMapping(
            com.dwinovo.numen.data.ModLanguageData.Keys.KEY_OPEN_ROSTER,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N,
            CATEGORY);

    /** R(按住)— 同伴轮盘:滚轮/指向选中当前交互对象,松开确认。 */
    public static final KeyMapping COMPANION_WHEEL = new KeyMapping(
            com.dwinovo.numen.data.ModLanguageData.Keys.KEY_COMPANION_WHEEL,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R,
            CATEGORY);

    /** Y — 快捷对话:对当前交互对象弹极简输入框(准星指着谁则优先谁)。 */
    public static final KeyMapping TALK_COMPANION = new KeyMapping(
            com.dwinovo.numen.data.ModLanguageData.Keys.KEY_TALK_COMPANION,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y,
            CATEGORY);

    /** V(按住)— 快捷语音:对讲机式,松开把转写发给当前交互对象。 */
    public static final KeyMapping QUICK_VOICE = new KeyMapping(
            com.dwinovo.numen.data.ModLanguageData.Keys.KEY_QUICK_VOICE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V,
            CATEGORY);

    private static boolean voiceWasDown;

    private NumenKeys() {}

    /** Per-client-tick poll; key presses only register while no screen is open. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_ROSTER.consumeClick()) {
            if (mc.player != null && mc.gui.screen() == null) {
                NumenScreen.openWorkspace();
            }
        }
        while (COMPANION_WHEEL.consumeClick()) {
            if (mc.player == null || mc.gui.screen() != null) {
                continue;
            }
            if (NumenRoster.instance().entries().isEmpty()) {
                com.dwinovo.numen.client.hud.TalkHint.flash("还没有同伴——先在 G 面板召唤一位", 3000);
                continue;
            }
            mc.gui.setScreen(new CompanionWheelScreen());
        }
        while (TALK_COMPANION.consumeClick()) {
            if (mc.player == null || mc.gui.screen() != null) {
                continue;
            }
            NumenRoster.Entry target = SelectedCompanion.resolveTarget();
            if (target == null) {
                com.dwinovo.numen.client.hud.TalkHint.flash(
                        "先按 [" + COMPANION_WHEEL.getTranslatedKeyMessage().getString()
                                + "] 选一位同伴,或把准星对准它", 3000);
                continue;
            }
            mc.gui.setScreen(new CompanionChatScreen(target.uuid(), target.name()));
        }
        // 快捷语音:按下沿开录,抬起沿(或任何界面弹开)收音发送
        boolean voiceDown = QUICK_VOICE.isDown() && mc.player != null && mc.gui.screen() == null;
        if (voiceDown && !voiceWasDown) {
            QuickVoice.press();
        } else if (!voiceDown && voiceWasDown) {
            QuickVoice.release();
        }
        voiceWasDown = voiceDown;
    }
}
