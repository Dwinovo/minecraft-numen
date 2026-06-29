package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Standalone global-settings screen (provider / API key / model / base URL),
 * reached from the {@code /numen} OPEN_SETTINGS verb. The {@link NumenScreen}
 * Settings tab embeds the same controls; both share {@link LlmProviders} +
 * {@link ProviderDropdown}, so the provider is picked from a dropdown rather than
 * a button grid.
 */
public final class SettingsScreen extends Screen {

    private static final int CONTENT_WIDTH = 300;
    private static final int CONTENT_HEIGHT = 190;
    private static final int LABEL_H = 11;
    private static final int FIELD_GAP = 37;

    private static final int TXT_MUTED = 0xFFAAAAAA;
    private static final int OK = 0xFF74D17A;

    private final Screen parent;

    private ProviderDropdown provider;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox baseUrlInput;
    private long savedFlashUntil;

    private int left, top;

    public SettingsScreen(Screen parent) {
        super(Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_TITLE));
        this.parent = parent;
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new SettingsScreen(parent));
    }

    @Override
    protected void init() {
        clearWidgets();
        this.left = (this.width - CONTENT_WIDTH) / 2;
        this.top = (this.height - CONTENT_HEIGHT) / 2;
        INumenConfig cfg = Services.CONFIG;

        int y = top + 10;
        provider = new ProviderDropdown(cfg.getProvider(), false);
        provider.setBounds(left, y + LABEL_H, CONTENT_WIDTH, 18);

        int y2 = y + FIELD_GAP;
        apiKeyInput = field(left, y2 + LABEL_H, 512, cfg.getApiKey());
        int y3 = y2 + FIELD_GAP;
        modelInput = field(left, y3 + LABEL_H, 128, cfg.getModel());
        int y4 = y3 + FIELD_GAP;
        baseUrlInput = field(left, y4 + LABEL_H, 256, cfg.getBaseUrl());

        int footerY = top + CONTENT_HEIGHT - 18;
        int btnW = 60;
        int rightX = left + CONTENT_WIDTH - btnW;
        addRenderableWidget(new SimpleButton(rightX - btnW - 4, footerY, btnW, 18,
                Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), b -> onClose()));
        addRenderableWidget(new SimpleButton(rightX, footerY, btnW, 18,
                Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_SAVE), b -> onSave()));
    }

    private EditBox field(int x, int y, int max, String value) {
        EditBox e = new EditBox(font, x, y, CONTENT_WIDTH, 18, Component.literal(""));
        e.setMaxLength(max);
        e.setValue(value == null ? "" : value);
        addRenderableWidget(e);
        return e;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(font, getTitle(), this.width / 2, top - 6, 0xFFFFFFFF);

        LlmProviders.Option opt = LlmProviders.byId(provider.selectedId());
        modelInput.setHint(Component.literal(opt.defaultModel()));
        baseUrlInput.setHint(Component.literal(opt.defaultBaseUrl()));

        int y = top + 10;
        g.drawString(font, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_PROVIDER), left, y, TXT_MUTED);
        g.drawString(font, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_API_KEY), left, y + FIELD_GAP, TXT_MUTED);
        g.drawString(font, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_MODEL), left, y + FIELD_GAP * 2, TXT_MUTED);
        g.drawString(font, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_BASE_URL), left, y + FIELD_GAP * 3, TXT_MUTED);

        if (savedFlashUntil > System.currentTimeMillis()) {
            g.drawString(font, Component.literal("✔ saved"), left, top + CONTENT_HEIGHT - 14, OK);
        }
        provider.render(g, font, mouseX, mouseY);   // last → open list overlays the fields
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && provider != null && provider.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void onSave() {
        INumenConfig cfg = Services.CONFIG;
        cfg.setProvider(provider.selectedId());
        cfg.setApiKey(apiKeyInput.getValue());
        cfg.setModel(modelInput.getValue());
        cfg.setBaseUrl(baseUrlInput.getValue());
        cfg.save();
        NumenLlmClient.reset();
        savedFlashUntil = System.currentTimeMillis() + 1500;
    }

    @Override
    public void onClose() {
        if (parent != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
