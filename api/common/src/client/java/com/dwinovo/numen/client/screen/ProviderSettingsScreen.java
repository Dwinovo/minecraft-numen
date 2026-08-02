package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.client.screen.settings.ProviderPanel;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.NumenToasts;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Button;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 提供商设置的独立屏(/numen 设置命令的门)——{@link ProviderPanel} 的薄壳:
 * 背景、标题、返回按钮、自己的 toast 实例,其余全是面板的瓤。同一个瓤也
 * 嵌在 G 面板的"连接"分区里。尺寸对标原版 GUI 的紧凑档,大屏不贪宽。
 */
public final class ProviderSettingsScreen extends Screen {

    private final Screen parent;
    private final NumenToasts toasts = new NumenToasts();
    private final NumenTheme.Colors colors = NumenTheme.DARK.colors();
    private final ProviderPanel panel = new ProviderPanel(
            (sev, msg) -> toastsPush(sev, msg));

    private com.dwinovo.numen.client.ui.widget.UiRoot shellUi;
    private int panelX, panelY, panelW, panelH;

    public ProviderSettingsScreen(Screen parent) {
        super(Component.translatable("numen.gui.providers.title"));
        this.parent = parent;
    }

    private void toastsPush(NumenToasts.Severity sev, String msg) {
        toasts.push(sev, msg);
    }

    public static void open(Screen parent) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new ProviderSettingsScreen(parent));
    }

    @Override
    protected void init() {
        panelW = Math.min(352, width - 16);
        panelH = Math.min(206, height - 16);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        panel.build(panelX, panelY + 16, panelW, panelH - 40, height);

        shellUi = new com.dwinovo.numen.client.ui.widget.UiRoot();
        Button back = shellUi.add(new Button(
                Component.translatable("gui.back").getString(),
                Button.Style.NORMAL, this::onClose));
        back.setBounds(panelX, panelY + panelH - 18, 48, 15);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        McDrawSurface s = new McDrawSurface(g, font);
        s.fillRoundRect(panelX - 6, panelY - 6, panelW + 12, panelH + 12, com.dwinovo.numen.client.ui.NumenStyle.RADIUS_PANEL, colors.panelBg());
        s.drawText(title.getString(), panelX, panelY + 1, colors.textPrimary(), false);
        s.fillRect(panelX, panelY + 12, 18, 1, colors.accent());
        panel.render(s, colors, mouseX, mouseY, Util.getMillis());
        shellUi.render(s, colors, mouseX, mouseY, Util.getMillis());
        toasts.render(s, width, colors, Util.getMillis());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (panel.mouseClicked(mx, my, button)) return true;
        if (shellUi.mouseClicked(mx, my, button)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        return panel.mouseScrolled(mx, my, scrollY) || super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (panel.keyPressed(keyCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        return panel.charTyped(ch) || super.charTyped(ch, modifiers);
    }

    @Override
    public void onClose() {
        NumenLlmClient.reset();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
