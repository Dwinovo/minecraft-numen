package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.MultilineTextField;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.persona.PersonaExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人格的编辑表单——名称、自由 Markdown 正文与插件贡献的扩展数据字段。
 * 两个多行框都支持软换行、选区、拖选、剪贴板和滚动。
 * 名称即文件名;名称和正文留空都是内联校验错误。
 * 编辑的是 {@link Draft} 草稿,保存才落盘(与旧表单同语义)。
 */
import com.dwinovo.numen.data.ModLanguageData;

public final class PersonaFormPanel {

    /** 表单草稿(id 由宿主管理;名称即 persona/ 目录里的文件名)。 */
    public static final class Draft {
        public String name = "";
        public String text = "";
        public final Map<String, String> extensionData = new LinkedHashMap<>();
    }

    private final UiRoot ui = new UiRoot();
    private final Consumer<Draft> onSave;
    private final Runnable onCancel;

    private Draft draft = new Draft();
    private TextField nameField;
    private MultilineTextField textArea;
    private final Map<String, MultilineTextField> extensionAreas = new LinkedHashMap<>();

    public PersonaFormPanel(Consumer<Draft> onSave, Runnable onCancel) {
        this.onSave = onSave;
        this.onCancel = onCancel;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
    }

    /** 载入待编辑的草稿(新建=空草稿;编辑=从条目拷来)。宿主随后 build。 */
    public void open(Draft d) {
        this.draft = d;
    }

    public void build(int x, int y, int w, int h) {
        ui.clear();
        extensionAreas.clear();

        int ry = y;
        Label nameLabel = ui.add(new Label(t("numen.persona.form_name"), Label.Role.MUTED));
        nameLabel.setBounds(x, ry, 200, 9);
        ry += NumenStyle.LABEL_PITCH;
        nameField = ui.add(new TextField(draft.name, v -> draft.name = v)
                .placeholder("名称(即文件名),如 小焰")
                .withLabel(nameLabel));
        nameField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        Label textLabel = ui.add(new Label(t("numen.persona.form_text"), Label.Role.MUTED));
        textLabel.setBounds(x, ry, 140, 9);
        ry += NumenStyle.LABEL_PITCH;
        textArea = ui.add(new MultilineTextField(draft.text, v -> draft.text = v)
                .placeholder(t("numen.persona.text_placeholder"))
                .maxLength(4096)
                .withLabel(textLabel));

        int editorBottom = y + h - 20;
        var extensions = NumenPlugins.personaExtensions();
        int extensionHeight = extensions.isEmpty() ? 0
                : Math.max(32, Math.min(68, (editorBottom - ry - 48) / extensions.size()));
        int extensionAreaTotal = extensions.size() * (extensionHeight + NumenStyle.LABEL_PITCH + 3);
        int promptHeight = Math.max(42, editorBottom - ry - extensionAreaTotal);
        textArea.setBounds(x, ry, w, promptHeight);
        ry += promptHeight + 3;

        for (PersonaExtension extension : extensions) {
            Label extensionLabel = ui.add(new Label(t(extension.editorLabelKey()), Label.Role.MUTED));
            extensionLabel.setBounds(x, ry, w, 9);
            ry += NumenStyle.LABEL_PITCH;
            String initial = draft.extensionData.getOrDefault(extension.id(), "");
            MultilineTextField area = ui.add(new MultilineTextField(initial,
                    value -> draft.extensionData.put(extension.id(), value))
                    .placeholder(t(extension.editorPlaceholderKey()))
                    .maxLength(extension.editorMaxLength())
                    .withLabel(extensionLabel));
            area.setBounds(x, ry, w, extensionHeight);
            extensionAreas.put(extension.id(), area);
            ry += extensionHeight + 3;
        }

        Button close = ui.add(new Button("✕", Button.Style.GHOST, onCancel));
        close.setBounds(x + w - 8, y - 14, 14, 14);
        Button save = ui.add(new Button(t("numen.gui.settings.save"),
                Button.Style.ACCENT, this::save));
        save.setBounds(x + w - 54, y + h - 16, 54, 15);
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    public boolean mouseDragged(double mx, double my, double dx, double dy) {
        return ui.mouseDragged(mx, my, dx, dy);   // 正文拖选
    }

    public boolean mouseReleased(double mx, double my, int button) {
        return ui.mouseReleased(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta);   // 正文编辑器自带滚动
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }

    // ---- 内部 ----

    private void save() {
        boolean ok = true;
        if (draft.name == null || draft.name.isBlank()) {
            nameField.setError(t(ModLanguageData.Keys.GUI_INLINE_REQUIRED));   // 校验错误内联在错误发生处
            ok = false;
        }
        if (draft.text == null || draft.text.isBlank()) {
            textArea.setError(t(ModLanguageData.Keys.GUI_INLINE_REQUIRED));
            ok = false;
        }
        try {
            com.dwinovo.numen.persona.PersonaLibrary.validateText(
                    com.dwinovo.numen.persona.PersonaLibrary.composeText(draft.text, draft.extensionData));
        } catch (IllegalArgumentException ex) {
            extensionAreas.values().stream().findFirst()
                    .ifPresent(area -> area.setError(t("numen.gui.settings.invalid_value")));
            ok = false;
        }
        if (ok) onSave.accept(draft);
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
