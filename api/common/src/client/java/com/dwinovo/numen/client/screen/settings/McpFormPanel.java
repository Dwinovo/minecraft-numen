package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * MCP 服务器的编辑表单——NumenUI 版的瓤:名称 + 类型自标注按钮
 * (http ↔ stdio,切换即换 URL/命令与请求头/环境变量两行的标签与占位符,
 * 已输入的值全保)+ 目标 + 附加项。名称/目标留空是内联校验错误;
 * spec 组装(命令切分/键值对解析)留在宿主。
 */
import com.dwinovo.numen.data.ModLanguageData;

public final class McpFormPanel {

    /** 表单草稿;{@code editOriginal} 非空 = 编辑替换该名字的条目。 */
    public static final class Draft {
        public String editOriginal;
        public boolean stdio;
        public String name = "";
        public String target = "";
        public String extra = "";
    }

    private final UiRoot ui = new UiRoot();
    private final Consumer<Draft> onSave;
    private final Runnable onCancel;

    private Draft draft = new Draft();
    private TextField nameField, targetField;
    private int formX, formY, formW, formH;

    public McpFormPanel(Consumer<Draft> onSave, Runnable onCancel) {
        this.onSave = onSave;
        this.onCancel = onCancel;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
    }

    /** 载入待编辑的草稿(新建=空草稿;编辑=从 spec 拷来)。宿主随后 build。 */
    public void open(Draft d) {
        this.draft = d;
    }

    public void build(int x, int y, int w, int h) {
        this.formX = x;
        this.formY = y;
        this.formW = w;
        this.formH = h;
        ui.clear();

        int ry = y;
        Label nameLabel = labelWidget(x, ry, "numen.mcp.form_name");
        ry += NumenStyle.LABEL_PITCH;
        nameField = ui.add(new TextField(draft.name, v -> draft.name = v).placeholder("kfc").withLabel(nameLabel));
        nameField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 类型行:自标注按钮循环 http ↔ stdio(草稿字段全保,行标签/占位符随切换)。
        Button typeBtn = ui.add(new Button(
                t(draft.stdio ? "numen.mcp.type_stdio" : "numen.mcp.type_http"),
                Button.Style.NORMAL, this::onTypeToggle));
        typeBtn.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH - NumenStyle.LABEL_PITCH + 4;

        ry = label(x, ry, draft.stdio ? "numen.mcp.form_command" : "numen.mcp.form_url");
        targetField = ui.add(new TextField(draft.target, v -> draft.target = v)
                .placeholder(draft.stdio ? "cmd /c npx -y <server>" : "https://mcp.mcd.cn"));
        targetField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 第四行:HTTP → 请求头 "Name: Value";stdio → 环境变量 "KEY=value"(';' 分隔)。
        ry = label(x, ry, draft.stdio ? "numen.mcp.form_env" : "numen.mcp.form_header");
        TextField extraField = ui.add(new TextField(draft.extra, v -> draft.extra = v)
                .placeholder(draft.stdio ? "KEY=value; KEY2=value2" : "Authorization: Bearer <token>"));
        extraField.setBounds(x, ry, w, NumenStyle.CONTROL_H);

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

    public boolean keyPressed(int keyCode, int modifiers) {
        return ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }

    // ---- 内部 ----

    private int label(int lx, int ly, String key) {
        labelWidget(lx, ly, key);
        return ly + NumenStyle.LABEL_PITCH;
    }

    /** 标签控件本体:会报错的字段用 withLabel 认领它(出错时自动让位)。 */
    private Label labelWidget(int lx, int ly, String key) {
        Label l = ui.add(new Label(t(key), Label.Role.MUTED));
        l.setBounds(lx, ly, 200, 9);
        return l;
    }

    private void onTypeToggle() {
        draft.stdio = !draft.stdio;
        build(formX, formY, formW, formH);   // 行标签/占位符随类型换,草稿值全保
    }

    private void save() {
        boolean ok = true;
        if (draft.name == null || draft.name.isBlank()) {
            nameField.setError(t(ModLanguageData.Keys.GUI_INLINE_REQUIRED));   // 校验错误内联在错误发生处
            ok = false;
        }
        if (draft.target == null || draft.target.isBlank()) {
            targetField.setError(t(ModLanguageData.Keys.GUI_INLINE_REQUIRED));
            ok = false;
        }
        if (ok) onSave.accept(draft);
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
