package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.MultilineTextField;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 人格的编辑表单——NumenUI 版的瓤:名称单行 + 正文多行编辑器
 * ({@link MultilineTextField}:软换行/选区/拖选/剪贴板/滚动)占满剩余高度。
 * 名称即文件名,正文是自由 Markdown;两者留空都是内联校验错误。
 * 编辑的是 {@link Draft} 草稿,保存才落盘(与旧表单同语义)。
 */
import com.dwinovo.numen.data.ModLanguageData;

public final class PersonaFormPanel {

    /** 表单草稿(id 由宿主管理;名称即 persona/ 目录里的文件名)。 */
    public static final class Draft {
        public String name = "";
        public String text = "";
    }

    private final UiRoot ui = new UiRoot();
    private final Consumer<Draft> onSave;
    private final Runnable onCancel;

    private Draft draft = new Draft();
    private TextField nameField;
    private MultilineTextField textArea;

    public PersonaFormPanel(Consumer<Draft> onSave, Runnable onCancel) {
        this.onSave = onSave;
        this.onCancel = onCancel;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** 载入待编辑的草稿(新建=空草稿;编辑=从条目拷来)。宿主随后 build。 */
    public void open(Draft d) {
        this.draft = d;
    }

    public void build(int x, int y, int w, int h) {
        ui.clear();

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
        // 正文占满剩余高度(编辑器自带滚动),底部留出按钮行。
        textArea = ui.add(new MultilineTextField(draft.text, v -> draft.text = v)
                .placeholder(t("numen.persona.text_placeholder"))
                .maxLength(4096)
                .withLabel(textLabel));
        textArea.setBounds(x, ry, w, (y + h - 20) - ry);

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
        if (ok) onSave.accept(draft);
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
