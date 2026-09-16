package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.widget.Badge;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.ConfirmDialog;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.Toggle;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.client.ui.widget.ValueRow;
import com.dwinovo.numen.mcp.server.McpConfig;
import com.dwinovo.numen.mcp.server.McpMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 外接大脑分区。
 *
 * <h2>两页,不是一页</h2>
 * 分区可用区是 282×196,一个「标签+控件」行占 {@value NumenStyle#ROW_PITCH}+
 * {@value NumenStyle#LABEL_PITCH}px —— 一屏放得下五行。而这里要摆的是三行只读、四项可编辑、
 * 状态、错误、两个动作,十行开外。所以拆成<b>概览</b>与<b>设置</b>两页:主人开这个面板十次
 * 有九次是来复制端点的,改端口是偶尔一次的事,把它们摆在一起两边都局促。
 *
 * <h2>只读行也是控件</h2>
 * 令牌走 {@link ValueRow},和表单行共用一个 {@code ry} 游标。只读文本用固定 Y 手绘
 * 的话,中间插一行就得把后面所有常量重排一遍。
 */
public final class BrainPanel {

    /** 概览页只读行的行距(比表单行矮:没有控件要放)。 */
    /** 行与行之间的步进:一行的高 + 一个小间距。 */
    private static final int ROW_PITCH = NumenStyle.CONTROL_H + NumenStyle.HEADER_GAP;
    private static final int COPY_W = 46;
    private static final int REGEN_W = 52;

    private final UiRoot ui = new UiRoot();
    /** 复制/保存回执:跨 build 持久(重建不吞在途消息)。 */
    private final InlineAlert notice = new InlineAlert();
    private final ConfirmDialog confirm = new ConfirmDialog();

    private boolean settingsPage;
    /** 设置页草稿——保存前不落地。 */
    private boolean lanDraft;
    private TextField portField, timeoutField, hiddenField;
    private Button saveButton;
    private Button tokenCopy, tokenRegen;
    private int x, y, w, h;
    private int dimX, dimY, dimW, dimH;

    public BrainPanel() {
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
    }

    /** 切分区时回到概览页,并丢弃未保存的草稿。 */
    public void reseed() {
        settingsPage = false;
    }

    public void build(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        ui.clear();
        portField = timeoutField = hiddenField = null;
        tokenCopy = tokenRegen = null;
        if (settingsPage) {
            buildSettings();
        } else {
            buildOverview();
        }
        ui.add(notice).setBounds(x, NumenStyle.bodyTop(y), w, 24);
    }

    /** 遮罩范围由宿主给——确认卡要盖住整个设置面板,不是只盖这个分区。 */
    public void setDimBounds(int dimX, int dimY, int dimW, int dimH) {
        this.dimX = dimX;
        this.dimY = dimY;
        this.dimW = dimW;
        this.dimH = dimH;
    }

    // ---- 概览 ----

    private void buildOverview() {
        McpMode mcp = McpMode.instance();

        Label title = ui.add(new Label(t("numen.brain.title"), Label.Role.PRIMARY));
        title.setBounds(x, NumenStyle.centerIn(y, NumenStyle.HEADER_H, 9), w - 140, 9);

        // 开关回调只写配置,绝不在此重建——重建会 new 出滑块已在终点的新 Toggle,
        // 滑动动画连起步都来不及(真机教训:大脑区开关瞬时切换的病根)。
        // 开关本身就是"开着还是关着",抬头不再另写一句;开着时抬头右边只报接上没接上(见 renderOverview)。
        Toggle tog = ui.add(new Toggle(mcp.enabled(), McpMode.instance()::setEnabled));
        tog.setBounds(x + w - 24, NumenStyle.centerIn(y, NumenStyle.HEADER_H, 11), 22, 11);

        // 地址是这一页的主角:一条带框的只读地址 + 复制,和 LM Studio 那类本地服务页同形。
        int ry = NumenStyle.bodyTop(y);
        endpointRow = ry;
        copyButton(x + w - COPY_W, ry, () -> McpMode.instance().endpoint());
        ry += ROW_PITCH;

        ui.add(new ValueRow(t("numen.brain.token"), this::tokenText)
                .dimWhen(() -> McpMode.instance().token().isBlank()))
                .setBounds(x, ry, w - REGEN_W - COPY_W - 8, NumenStyle.CONTROL_H);
        tokenCopy = copyButton(x + w - REGEN_W - 4 - COPY_W, ry, () -> McpMode.instance().token());
        tokenRegen = ui.add(new Button(t("numen.brain.regenerate"), Button.Style.NORMAL,
                this::askRegenerate));
        tokenRegen.setBounds(x + w - REGEN_W, ry, REGEN_W, NumenStyle.CONTROL_H);
        ry += ROW_PITCH + NumenStyle.HEADER_GAP;

        // 接上外部 AI 就这一步:提示词里已经含着地址与令牌。说明跟在按钮下面,小一号的灰字。
        String promptLabel = t("numen.brain.copy_prompt");
        int pw = Minecraft.getInstance().font.width(promptLabel) + 14;
        Button prompt = ui.add(new Button(promptLabel, Button.Style.ACCENT,
                () -> copy(mcp.accessPrompt())));
        prompt.setBounds(x, ry, pw, NumenStyle.CONTROL_H);
        Label warn = ui.add(new Label(t("numen.brain.prompt_warn"), Label.Role.MUTED));
        warn.setBounds(x, ry + NumenStyle.CONTROL_H + 4, w, 9);
        promptRow = ry;

        Button settings = ui.add(new Button(t("numen.brain.settings"), Button.Style.NORMAL,
                () -> switchTo(true)));
        settings.setBounds(x + w - 54, NumenStyle.footerTop(y, h), 54, NumenStyle.CONTROL_H);
    }

    /** 地址那一行的顶边:框与地址在 render 里画(值随端口、局域网开关变)。 */
    private int endpointRow;
    /** 设置页两个开关行的顶边:说明画在行下面(见 {@link #noteBelow}）。 */
    private int lanRow;
    private int quietRow;

    /** 开关行下面那行说明的顶边。 */
    private static int noteBelow(int row) { return row + NumenStyle.CONTROL_H + 1; }
    /** 复制提示词那一行的顶边:启动失败的红字画在说明下面(平时没有这一行)。 */
    private int promptRow;

    // ---- 设置 ----

    private void buildSettings() {
        McpConfig cfg = McpMode.instance().config();

        Label title = ui.add(new Label(t("numen.brain.settings_title"), Label.Role.PRIMARY));
        title.setBounds(x, NumenStyle.centerIn(y, NumenStyle.HEADER_H, 9), w - 60, 9);
        Button back = ui.add(new Button(t("numen.brain.back"), Button.Style.NORMAL,
                () -> switchTo(false)));
        back.setBounds(x + w - 44, y, 44, NumenStyle.HEADER_H);
        int body = NumenStyle.bodyTop(y);

        // 「允许局域网」是 host 的人话面:关=127.0.0.1,开=0.0.0.0。玩家不必知道那五个字符,
        // 想绑具体网卡的高级用户改 config/numen/mcp_server.json —— 配置文件就是逃生舱。
        lanDraft = cfg.lanExposed();
        lanRow = body;
        Label lanLabel = ui.add(new Label(t("numen.brain.lan"), Label.Role.MUTED));
        lanLabel.setBounds(x, NumenStyle.centerIn(lanRow, NumenStyle.CONTROL_H, 9), w - 28, 9);
        Toggle lan = ui.add(new Toggle(lanDraft, on -> {
            lanDraft = on;
            refreshSaveState();
        }));
        lan.setBounds(x + w - 24, NumenStyle.centerIn(lanRow, NumenStyle.CONTROL_H, 11), 22, 11);

        // 开着时下面那行说明只在开着时出现,位置照样留着,免得下面的行跟着跳。
        int ry = noteBelow(lanRow) + 9 + 5;
        int half = (w - 12) / 2;
        Label portLabel = ui.add(new Label(t("numen.brain.port"), Label.Role.MUTED));
        portLabel.setBounds(x, ry, half, 9);
        Label toLabel = ui.add(new Label(t("numen.brain.timeout"), Label.Role.MUTED));
        toLabel.setBounds(x + half + 12, ry, half, 9);
        ry += NumenStyle.LABEL_PITCH;

        portField = ui.add(new TextField(String.valueOf(cfg.port()), v -> refreshSaveState())
                .numeric());
        portField.setBounds(x, ry, half, NumenStyle.CONTROL_H);
        timeoutField = ui.add(new TextField(String.valueOf(cfg.callTimeoutSeconds()),
                v -> refreshSaveState()).numeric());
        timeoutField.setBounds(x + half + 12, ry, half, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH + 10;

        Label hiddenLabel = ui.add(new Label(t("numen.brain.hidden_tools"), Label.Role.MUTED));
        hiddenLabel.setBounds(x, ry, w, 9);
        ry += NumenStyle.LABEL_PITCH;
        hiddenField = ui.add(new TextField(String.join(", ", cfg.hiddenTools()), v -> { })
                .placeholder(t("numen.brain.hidden_hint")));
        hiddenField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH + 6;

        // 外部 AI 久不动手时她怎么办:接着自己想,还是停下等。即时写配置,与主开关同一个"拨了就算"风格。
        Label quietLabel = ui.add(new Label(
                I18n.get("numen.brain.quiet_toggle", McpMode.QUIET_AFTER_MS / 60_000L), Label.Role.MUTED));
        quietLabel.setBounds(x, NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 9), w - 28, 9);
        Toggle quiet = ui.add(new Toggle(cfg.quietFallback(), McpMode.instance()::setQuietFallback));
        quiet.setBounds(x + w - 24, NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 11), 22, 11);
        quietRow = ry;

        saveButton = ui.add(new Button(saveLabel(), Button.Style.ACCENT, this::save));
        saveButton.setBounds(x + w - 96, NumenStyle.footerTop(y, h), 96, NumenStyle.CONTROL_H);
        refreshSaveState();
    }

    /** 端点改了且服务在跑 → 这次保存要重开服务,按钮如实说。 */
    private String saveLabel() {
        McpConfig cfg = McpMode.instance().config();
        boolean endpointChanged = portDraft() != cfg.port() || lanDraft != cfg.lanExposed();
        return t(McpMode.instance().enabled() && endpointChanged
                ? "numen.brain.save_restart" : "numen.brain.save");
    }

    private int portDraft() {
        return portField == null ? McpMode.instance().config().port() : portField.intValue(-1);
    }

    /**
     * 每次改动都重算一遍能不能保存。
     *
     * <p>拦得最死的一条:<b>开了局域网、令牌却是空的</b>。配置注释里那句"空令牌在回环上无害"
     * 的前提是回环,地址一放开就是谁都能操控主人的同伴。红字挂在令牌那一行,不弹全局警告。
     */
    private void refreshSaveState() {
        if (saveButton == null || portField == null) {
            return;
        }
        int port = portDraft();
        boolean portOk = port >= 1 && port <= 65535;
        portField.setError(portOk ? null : t("numen.brain.port_range"));
        boolean tokenOk = !lanDraft || !McpMode.instance().token().isBlank();
        saveButton.setEnabled(portOk && tokenOk);
        saveButton.setLabel(saveLabel());
    }

    private void save() {
        McpConfig cfg = McpMode.instance().config();
        List<String> hidden = new ArrayList<>();
        for (String piece : hiddenField.value().split(",")) {
            String name = piece.strip();
            if (!name.isEmpty()) hidden.add(name);
        }
        boolean ok = McpMode.instance().applySettings(
                lanDraft ? McpConfig.ANY_HOST : McpConfig.LOOPBACK,
                portDraft(),
                Math.max(1, timeoutField.intValue(cfg.callTimeoutSeconds())),
                hidden,
                McpMode.instance().token());
        if (ok) {
            notice.show(InlineAlert.Severity.SUCCESS, t("numen.brain.saved"), 2_000);
            switchTo(false);
        } else {
            // 起服失败最常见的就是端口被占用——把话挂回出错的那个框,别飘在别处
            portField.setError(I18n.get("numen.brain.port_taken", portDraft()));
        }
    }

    // ---- 令牌 ----

    /**
     * 换令牌要过确认卡。
     *
     * <p>我们自己把明文令牌嵌进「接入提示词」、并教主人复制给外部 AI——那就必须给他一条
     * 作废的路。而作废是有代价的:在线的客户端会当场断开,得说清楚再让他点。
     */
    private void askRegenerate() {
        confirm.open(ui, dimX, dimY, dimW, dimH,
                t("numen.brain.regen_confirm_title") + "\n" + t("numen.brain.regen_confirm_body"),
                t("numen.gui.settings.cancel"), t("numen.brain.regenerate"),
                () -> {
                    McpConfig cfg = McpMode.instance().config();
                    McpMode.instance().applySettings(cfg.host(), cfg.port(),
                            cfg.callTimeoutSeconds(), cfg.hiddenTools(), McpConfig.mintToken());
                    notice.show(InlineAlert.Severity.SUCCESS, t("numen.brain.saved"), 2_000);
                });
    }

    private String tokenText() {
        McpMode mcp = McpMode.instance();
        return mcp.token().isBlank() ? t("numen.brain.token_none") : mcp.maskedToken();
    }

    // ---- 渲染 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (settingsPage) {
            renderSettings(s, c);
        } else {
            renderOverview(s, c);
        }
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    private void renderOverview(IDrawSurface s, NumenTheme.Colors c) {
        McpMode mcp = McpMode.instance();
        boolean on = mcp.enabled();

        // 地址框:只读,像输入框一样有个框,右边就是复制——一眼看出"这条是拿去填给 AI 的"。
        int fieldW = w - COPY_W - 4;
        NumenStyle.box(s, x, endpointRow, fieldW, NumenStyle.CONTROL_H, c.inputBg(), c.inputBorder());
        s.drawText(TextClip.fit(s, mcp.endpoint(), fieldW - NumenStyle.FIELD_PAD * 2),
                x + NumenStyle.FIELD_PAD, NumenStyle.centerIn(endpointRow, NumenStyle.CONTROL_H, s.lineHeight()),
                c.textPrimary(), false);

        // 开着时抬头右边报一句接上没接上(等待接入 / 谁在用 · 多久前);关着时开关自己就说明了,不写字。
        if (on) {
            String badge = statusLine(mcp);
            int bw = Minecraft.getInstance().font.width(badge) + 8;
            Badge.draw(s, badge, x + w - 30 - bw,
                    NumenStyle.centerIn(y, NumenStyle.HEADER_H, s.lineHeight()),
                    mcp.clientName() == null ? c.warning() : c.success(), 0xFFFFFFFF);
        }

        // 启动失败是唯一要另占一行的事,画在动作那一行下面
        String err = mcp.lastError();
        if (err != null) {
            s.drawText(I18n.get("numen.brain.start_failed", err), x,
                    promptRow + NumenStyle.CONTROL_H + 4 + 13, c.danger(), false);
        }
        if (tokenCopy != null) tokenCopy.setVisible(!mcp.token().isBlank());
        if (tokenRegen != null) tokenRegen.setVisible(true);
    }

    private void renderSettings(IDrawSurface s, NumenTheme.Colors c) {
        if (lanDraft) {
            // 绑到所有网卡这件事本身会成功,只是降级——按自家判据是 warning 不是 danger。
            // 但令牌为空时它就变成"这次保存不该发生",那才是 danger。
            boolean noToken = McpMode.instance().token().isBlank();
            s.drawText(t(noToken ? "numen.brain.lan_needs_token" : "numen.brain.lan_warn"),
                    x, noteBelow(lanRow), noToken ? c.danger() : c.warning(), false);
        }
        // 开着关着各是什么结果,当场写在开关下面——这一句比开关名更要紧
        s.drawText(t(McpMode.instance().config().quietFallback()
                        ? "numen.brain.quiet_on" : "numen.brain.quiet_off"),
                x, noteBelow(quietRow), c.textMuted(), false);
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

    private void switchTo(boolean settings) {
        settingsPage = settings;
        build(x, y, w, h);
    }

    /** 复制按钮:文本惰性取(配置随时可变,build 时捕获会复制到过期值)。 */
    private Button copyButton(int bx, int by, java.util.function.Supplier<String> text) {
        Button b = ui.add(new Button(t("numen.brain.copy"), Button.Style.NORMAL,
                () -> copy(text.get())));
        b.setBounds(bx, by, COPY_W, NumenStyle.CONTROL_H);
        return b;
    }

    private void copy(String text) {
        ui.copyToClipboard(text);
        notice.show(InlineAlert.Severity.SUCCESS, t("numen.brain.copied"), 1_500);
    }

    /** 连接状态一行:没开 → 关闭;开着没人连 → 等待接入;连过 → 谁 + 多久前活跃。 */
    /** 开着时的一句:等谁来接,或者谁在用、多久前活跃过。关着不说——开关自己就说明了。 */
    private static String statusLine(McpMode mcp) {
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
