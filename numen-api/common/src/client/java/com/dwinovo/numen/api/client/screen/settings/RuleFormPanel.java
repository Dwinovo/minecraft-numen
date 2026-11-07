package com.dwinovo.numen.api.client.screen.settings;

import com.dwinovo.numen.api.client.agent.RemoteLibrary;
import com.dwinovo.numen.api.client.agent.ServerLibraries;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextWrap;
import com.dwinovo.numen.api.client.ui.widget.Button;
import com.dwinovo.numen.api.client.ui.widget.Label;
import com.dwinovo.numen.api.client.ui.widget.TextField;
import com.dwinovo.numen.api.client.ui.widget.UiRoot;
import com.dwinovo.numen.api.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * 加一行规则的表单卡:选判决(拒绝、询问、允许),写规则({@code break(owner & #c:chests)})。规则对不对由服务端的规则核心判,
 * 这里不解析:写错了,服务端的答复带着错在哪,就地显示在输入框下面(和命令里是同一句教学式的错误),卡不关;办成了才关。
 * 等答复的这一个来回里,保存钮锁着。
 */
public final class RuleFormPanel {

    /** 判决的三格,次序同分段钮。 */
    private static final List<String> TABLES = List.of("deny", "ask", "allow");

    /** 表单草稿。 */
    public static final class Draft {
        int table;
        String rule = "";
    }

    private final UiRoot ui = new UiRoot();
    private final Runnable onDone;
    private final Runnable onCancel;

    private Draft draft = new Draft();
    private TextField ruleField;
    private Button saveButton;
    /** 发出去等答复:答复按请求号交回 {@link #settle},推送插队不影响。 */
    private boolean awaiting;

    /**
     * @param onDone   服务端办成了:卡该收了
     * @param onCancel 主人取消
     */
    public RuleFormPanel(Runnable onDone, Runnable onCancel) {
        this.onDone = onDone;
        this.onCancel = onCancel;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(), s -> mc.keyboardHandler.setClipboard(s));
        ui.setInputFactory(com.dwinovo.numen.api.client.ui.mc.McTextInput.factory());
    }

    /** 每次打开从空白开始。宿主随后 build。 */
    public void open() {
        draft = new Draft();
        awaiting = false;
    }

    public void build(int x, int y, int w, int h) {
        ui.clear();
        var font = Minecraft.getInstance().font;
        int ry = y;
        ui.add(new Label(t(ModLanguageData.Keys.PERMISSION_FORM_VERDICT), Label.Role.SECONDARY)).setBounds(x, ry, w, 9);
        ry += NumenStyle.LABEL_PITCH;
        List<String> names = List.of(t(ModLanguageData.Keys.PERMISSION_TABLE_PREFIX + "deny"),
                t(ModLanguageData.Keys.PERMISSION_TABLE_PREFIX + "ask"),
                t(ModLanguageData.Keys.PERMISSION_TABLE_PREFIX + "allow"));
        Segmented verdict = ui.add(new Segmented(names, () -> draft.table, pick -> draft.table = pick));
        verdict.setBounds(x, ry, Math.min(w, Segmented.widthFor(names, font::width)), NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        Label ruleLabel = ui.add(new Label(t(ModLanguageData.Keys.PERMISSION_FORM_RULE), Label.Role.SECONDARY));
        ruleLabel.setBounds(x, ry, w, 9);
        ry += NumenStyle.LABEL_PITCH;
        ruleField = ui.add(new TextField(draft.rule, v -> {
            draft.rule = v;
            ruleField.clearError();
        }).placeholder("break(owner & #c:chests)").withLabel(ruleLabel));
        ruleField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.CONTROL_H + 6;

        // 写法的一句提示;放不下的折行,不交给卡的边去切
        int lineH = font.lineHeight + 1;
        List<String> hint = TextWrap.wrap(t(ModLanguageData.Keys.PERMISSION_FORM_HINT), w, font::width, 0);
        for (int i = 0; i < hint.size(); i++) {
            ui.add(new Label(hint.get(i), Label.Role.SECONDARY)).setBounds(x, ry + i * lineH, w, 9);
        }

        saveButton = DialogButtons.cancelSave(ui, x, y, w, h, onCancel, this::save);
        saveButton.setEnabled(!awaiting);
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

    private void save() {
        if (draft.rule == null || draft.rule.isBlank()) {
            ruleField.setError(t(ModLanguageData.Keys.GUI_INLINE_REQUIRED));
            return;
        }
        awaiting = true;
        saveButton.setEnabled(false);
        ServerLibraries.addRule(TABLES.get(draft.table), draft.rule.strip(), this::settle);
    }

    /** 服务端答了:办成就收卡;规则写错了,把错在哪挂回输入框,保存钮放开让主人改。 */
    void settle(RemoteLibrary.Reply reply) {
        awaiting = false;
        saveButton.setEnabled(true);
        if (reply.ok()) {
            onDone.run();
            return;
        }
        // 答复里的表此刻就是当前的表,错在哪的那一行在里面
        ServerLibraries.RuleMistake mistake = ServerLibraries.permission().mistake();
        ruleField.setError(mistake == null ? reply.problem()
                : I18n.get(ModLanguageData.Keys.COMMAND_RULE_MISTAKE_PREFIX + mistake.code(), mistake.args().toArray()));
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
