package com.dwinovo.numen.api.client.screen.settings;

import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.widget.Label;
import com.dwinovo.numen.api.client.ui.widget.TextField;
import com.dwinovo.numen.api.client.ui.widget.UiRoot;
import com.dwinovo.numen.api.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 把授权完成后浏览器地址栏里的网址贴回来:服务器不在这台机器上时,浏览器跳回的是这台机器自己的回环地址,页面打不开,
 * 地址栏里的网址却带着授权码——服务端从中取码完成授权。一个输入框,留空是内联校验错误。
 */
public final class McpAuthFormPanel {

    private final UiRoot ui = new UiRoot();
    private final Consumer<String> onSubmit;
    private final Runnable onCancel;

    private String url = "";
    private TextField urlField;

    public McpAuthFormPanel(Consumer<String> onSubmit, Runnable onCancel) {
        this.onSubmit = onSubmit;
        this.onCancel = onCancel;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        ui.setInputFactory(com.dwinovo.numen.api.client.ui.mc.McTextInput.factory());
    }

    /** 每次打开从空白开始。宿主随后 build。 */
    public void open() {
        url = "";
    }

    public void build(int x, int y, int w, int h) {
        ui.clear();
        Label hint = ui.add(new Label(t("numen.mcp.auth_hint"), Label.Role.SECONDARY));
        hint.setBounds(x, y, w, 9);
        int ry = y + NumenStyle.LABEL_PITCH + 4;
        urlField = ui.add(new TextField(url, v -> url = v).placeholder("http://127.0.0.1:…/callback?code=…"));
        urlField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        DialogButtons.cancelSave(ui, x, y, w, h, onCancel, this::submit);
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }

    // ---- 内部 ----

    private void submit() {
        if (url == null || url.isBlank()) {
            urlField.setError(t(ModLanguageData.Keys.GUI_INLINE_REQUIRED));   // 校验错误内联在错误发生处
            return;
        }
        onSubmit.accept(url.strip());
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
