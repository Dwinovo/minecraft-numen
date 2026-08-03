package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.Toggle;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.mcp.server.McpMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * 外接大脑分区——NumenUI 版的瓤:总开关 + 端点/令牌只读展示(明文令牌
 * 不上屏,整份走复制)+ 三个复制按钮 + 连接状态行;复制回执走页面级
 * 绿胶囊(替代旧 savedFlash 闪字)。状态文本每帧现取现画(McpMode 是活的)。
 */
public final class BrainPanel {

    /** 各行纵向锚点(相对分区顶):与旧版同一布局节律。 */
    private static final int R_TOGGLE = 18, R_HINT = 34, R_ENDPOINT = 58,
            R_TOKEN = 88, R_PROMPT = 120, R_WARN = 142, R_STATUS = 160;

    private final UiRoot ui = new UiRoot();
    /** 复制回执:跨 build 持久(开关重建不吞在途消息)。 */
    private final InlineAlert notice = new InlineAlert();
    private Button tokenCopy;
    private int x, y, w, h;

    public BrainPanel() {
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    public void build(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        ui.clear();
        McpMode mcp = McpMode.instance();

        Label title = ui.add(new Label(t("numen.brain.title"), Label.Role.PRIMARY));
        title.setBounds(x, y, w, 9);

        // 开关回调只写配置,绝不在此重建——重建会 new 出滑块已在终点的新 Toggle,
        // 滑动动画连起步都来不及(真机教训:大脑区开关瞬时切换的病根)。
        Toggle tog = ui.add(new Toggle(mcp.enabled(), McpMode.instance()::setEnabled));
        tog.setBounds(x + w - 24, y + R_TOGGLE - 1, 22, 11);

        // 端点/令牌的复制按钮常驻;令牌未生成时由 render 收起(见 tokenCopy.setVisible)。
        copyButton(x + w - 50, y + R_ENDPOINT + 9, () -> McpMode.instance().endpoint());
        tokenCopy = copyButton(x + w - 50, y + R_TOKEN + 9, () -> McpMode.instance().token());
        String promptLabel = t("numen.brain.copy_prompt");
        int pw = Minecraft.getInstance().font.width(promptLabel) + 14;   // 宽随文案实测
        Button prompt = ui.add(new Button(promptLabel, Button.Style.ACCENT,
                () -> copy(mcp.accessPrompt())));
        prompt.setBounds(x, y + R_PROMPT, pw, 16);

        ui.add(notice).setBounds(x, y + 14, w, 24);
    }

    /** 只读文本每帧现画:开关提示/错误/端点/掩码令牌/告诫/状态都可能随时变。 */
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        McpMode mcp = McpMode.instance();
        boolean on = mcp.enabled();

        s.drawText(t("numen.brain.toggle"), x, y + R_TOGGLE, c.textPrimary(), false);
        s.drawText(t(on ? "numen.brain.hint_on" : "numen.brain.hint_off"),
                x, y + R_HINT, c.textMuted(), false);
        String err = mcp.lastError();
        if (err != null) {
            s.drawText(I18n.get("numen.brain.start_failed", err), x, y + R_HINT + 10,
                    c.danger(), false);
        }

        s.drawText(t("numen.brain.endpoint"), x, y + R_ENDPOINT, c.textMuted(), false);
        s.drawText(mcp.endpoint(), x, y + R_ENDPOINT + 11, c.textPrimary(), false);

        s.drawText(t("numen.brain.token"), x, y + R_TOKEN, c.textMuted(), false);
        // 明文令牌不上屏:截图/录屏泄露一次就永久泄露,要整份走复制按钮。
        boolean noToken = mcp.token().isBlank();
        s.drawText(noToken ? t("numen.brain.token_none") : mcp.maskedToken(),
                x, y + R_TOKEN + 11, noToken ? c.textMuted() : c.textPrimary(), false);
        if (tokenCopy != null) tokenCopy.setVisible(!noToken);   // 无令牌可复制时收起

        s.drawText(t("numen.brain.prompt_warn"), x, y + R_WARN, c.textMuted(), false);
        s.drawText(statusLine(mcp), x, y + R_STATUS, on ? c.success() : c.textMuted(), false);

        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    // ---- 内部 ----

    /** 复制按钮:文本惰性取(配置随时可变,build 时捕获会复制到过期值)。 */
    private Button copyButton(int bx, int by, java.util.function.Supplier<String> text) {
        Button b = ui.add(new Button(t("numen.brain.copy"), Button.Style.NORMAL,
                () -> copy(text.get())));
        b.setBounds(bx, by, 46, 14);
        return b;
    }

    private void copy(String text) {
        ui.copyToClipboard(text);
        notice.show(InlineAlert.Severity.SUCCESS, t("numen.brain.copied"), 1_500);
    }

    /** 连接状态一行:没开 → 关闭;开着没人连 → 等待接入;连过 → 谁 + 多久前活跃。 */
    private static String statusLine(McpMode mcp) {
        if (!mcp.enabled()) return t("numen.brain.status_off");
        String who = mcp.clientName();
        if (who == null) return t("numen.brain.status_waiting");
        return I18n.get("numen.brain.status_connected", who, sinceLabel(mcp.lastActivityMs()));
    }

    private static String sinceLabel(long stampMs) {
        long sec = Math.max(0, (System.currentTimeMillis() - stampMs) / 1000);
        if (sec < 60) return I18n.get("numen.brain.since_sec", sec);
        return I18n.get("numen.brain.since_min", sec / 60);
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
