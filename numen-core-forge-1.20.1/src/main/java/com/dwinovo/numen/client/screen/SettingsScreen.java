package com.dwinovo.numen.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Compatibility entry for the /numen settings action. The actual editor now
 * lives in the vanilla-style Settings page of {@link NumenScreen}; retaining a
 * second configuration screen would bring the discarded GUI back indirectly.
 */
public final class SettingsScreen extends Screen {

    private final Screen parent;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Numen AI 设置"));
        this.parent = parent;
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new SettingsScreen(parent));
    }

    @Override
    protected void init() {
        NumenScreen.openSettings(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
