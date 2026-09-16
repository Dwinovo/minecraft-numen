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
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 外接大脑分区——一页说完。
 *
 * <h2>一页,不是两页</h2>
 * 按用途排成两段:先是<b>拿去给外部 AI 的东西</b>(地址、令牌),再是<b>调它的旋钮</b>
 * (调用超时、不暴露的工具、外部 AI 不动手时她怎么办)。端口与局域网不跟旋钮走,而是紧贴
 * 地址下面——它们改的就是上面那条地址,放在一起因果才看得见。收尾行左边复制接入提示词、
 * 右边保存。藏一层子页的代价是主人得先发现那个按钮能点,不值当。
 *
 * <h2>行的契约</h2>
 * 一行高 {@link NumenStyle#CONTROL_H},标签在行内垂直居中,行尾的开关与按钮贴行的边;
 * 要解释的行在 {@link #noteBelow} 那一行写一句小灰字。整页一个 {@code ry} 游标从上排到下,
 * 中间插一行不必把后面的常量重排一遍。
 */
public final class BrainPanel {

    private static final int COPY_W = 46;
    private static final int REGEN_W = 52;
    private static final int SAVE_W = 96;
    private static final int PORT_LABEL_W = 28;
    private static final int PORT_FIELD_W = 56;
    private static final int TIMEOUT_LABEL_W = 72;
    private static final int TIMEOUT_FIELD_W = 44;
    /** 右半列(不暴露的工具):标签与输入框都贴着右边沿排。 */
    private static final int HIDDEN_LABEL_W = 68;
    private static final int HIDDEN_FIELD_W = 72;

    private final UiRoot ui = new UiRoot();
    /** 页面级回执(已复制/已保存):跨 build 持久,重建不吞在途消息。 */
    private final InlineAlert notice = new InlineAlert();
    private final ConfirmDialog confirm = new ConfirmDialog();

    /** 端口/局域网/超时/不暴露的工具是草稿,保存前不落地;两个开关是拨了就算。 */
    private boolean lanDraft;
    private TextField portField, timeoutField, hiddenField;
    private Button saveButton;
    private Button tokenCopy;
    private int x, y, w, h;
    private int dimX, dimY, dimW, dimH;

    /** 地址那一行的顶边:框与地址在 render 里画(值随端口、局域网开关变)。 */
    private int endpointRow;
    /** 两个要解释的行的顶边:说明画在行下面(见 {@link #noteBelow})。 */
    private int lanRow, quietRow;
    /** 动作行上面那一行的顶边:页面的话筒(起服失败 / 提示词提醒 / 回执胶囊)。 */
    private int msgRow;

    public BrainPanel() {
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
    }

    public void build(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        ui.clear();
        McpMode mcp = McpMode.instance();
        McpConfig cfg = mcp.config();
        Font font = Minecraft.getInstance().font;

        Label title = ui.add(new Label(t("numen.brain.title"), Label.Role.PRIMARY));
        title.setBounds(x, NumenStyle.centerIn(y, NumenStyle.HEADER_H, 9), w - 140, 9);
        // 开关回调只写配置,绝不在此重建——重建会 new 出滑块已在终点的新 Toggle,
        // 滑动动画连起步都来不及(真机教训:大脑区开关瞬时切换的病根)。
        // 开关本身就是"开着还是关着",抬头不另写一句;开着时抬头右边报接上没接上(见 render)。
        Toggle tog = ui.add(new Toggle(mcp.enabled(), McpMode.instance()::setEnabled));
        tog.setBounds(x + w - 24, NumenStyle.centerIn(y, NumenStyle.HEADER_H, 11), 22, 11);

        // 地址是这一页的主角:带框的只读地址 + 复制,和 LM Studio 那类本地服务页同形。
        int ry = NumenStyle.bodyTop(y);
        endpointRow = ry;
        copyButton(x + w - COPY_W, ry, () -> McpMode.instance().endpoint());
        ry += NumenStyle.ROW_PITCH;

        // 端口与「允许局域网」= 上面那条地址的两截。后者是 host 的人话面:关=127.0.0.1,
        // 开=0.0.0.0。玩家不必知道那五个字符,想绑具体网卡的高级用户改
        // config/numen/mcp_server.json —— 配置文件就是逃生舱。
        lanRow = ry;
        Label portLabel = ui.add(new Label(t("numen.brain.port"), Label.Role.MUTED));
        portLabel.setBounds(x, NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 9), PORT_LABEL_W, 9);
        portField = ui.add(new TextField(String.valueOf(cfg.port()), v -> refreshSaveState())
                .numeric());
        portField.setBounds(x + PORT_LABEL_W + 4, ry, PORT_FIELD_W, NumenStyle.CONTROL_H);
        lanDraft = cfg.lanExposed();
        String lanText = t("numen.brain.lan");
        int lanW = Math.min(font.width(lanText), w - PORT_LABEL_W - PORT_FIELD_W - 44);
        Label lanLabel = ui.add(new Label(lanText, Label.Role.MUTED));
        lanLabel.setBounds(x + w - 28 - lanW, NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 9),
                lanW, 9);
        Toggle lan = ui.add(new Toggle(lanDraft, on -> {
            lanDraft = on;
            refreshSaveState();
        }));
        lan.setBounds(x + w - 24, NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 11), 22, 11);
        // 放开局域网时行下面多一句警示,位置常留着,免得下面的行跟着跳。
        ry = noteBelow(lanRow) + 9 + 5;

        ui.add(new ValueRow(t("numen.brain.token"), this::tokenText)
                .dimWhen(() -> McpMode.instance().token().isBlank()))
                .setBounds(x, ry, w - REGEN_W - COPY_W - 8, NumenStyle.CONTROL_H);
        tokenCopy = copyButton(x + w - REGEN_W - 4 - COPY_W, ry, () -> McpMode.instance().token());
        Button tokenRegen = ui.add(new Button(t("numen.brain.regenerate"), Button.Style.NORMAL,
                this::askRegenerate));
        tokenRegen.setBounds(x + w - REGEN_W, ry, REGEN_W, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 两个冷门旋钮合一行:标签贴着各自的框,右边那一列连同框贴住右边沿,列间留一道槽。
        Label toLabel = ui.add(new Label(t("numen.brain.timeout"), Label.Role.MUTED));
        toLabel.setBounds(x, NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 9), TIMEOUT_LABEL_W, 9);
        timeoutField = ui.add(new TextField(String.valueOf(cfg.callTimeoutSeconds()),
                v -> refreshSaveState()).numeric());
        timeoutField.setBounds(x + TIMEOUT_LABEL_W + 4, ry, TIMEOUT_FIELD_W, NumenStyle.CONTROL_H);
        Label hiddenLabel = ui.add(new Label(t("numen.brain.hidden_tools"), Label.Role.MUTED));
        hiddenLabel.setBounds(x + w - HIDDEN_FIELD_W - 4 - HIDDEN_LABEL_W,
                NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 9), HIDDEN_LABEL_W, 9);
        hiddenField = ui.add(new TextField(String.join(", ", cfg.hiddenTools()), v -> { })
                .placeholder(t("numen.brain.hidden_hint")));
        hiddenField.setBounds(x + w - HIDDEN_FIELD_W, ry, HIDDEN_FIELD_W, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 外部 AI 久不动手时她怎么办:接着自己想,还是停下等。即时写配置,与主开关同一个"拨了就算"。
        quietRow = ry;
        Label quietLabel = ui.add(new Label(
                I18n.get("numen.brain.quiet_toggle", McpMode.QUIET_AFTER_MS / 60_000L),
                Label.Role.MUTED));
        quietLabel.setBounds(x, NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 9), w - 28, 9);
        Toggle quiet = ui.add(new Toggle(cfg.quietFallback(), McpMode.instance()::setQuietFallback));
        quiet.setBounds(x + w - 24, NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 11), 22, 11);

        // 收尾行:左边是主人来这一页最常做的事,右边是把草稿落地。
        int footer = NumenStyle.footerTop(y, h);
        msgRow = footer - 5 - 9;
        String promptLabel = t("numen.brain.copy_prompt");
        Button prompt = ui.add(new Button(promptLabel, Button.Style.ACCENT,
                () -> copy(McpMode.instance().accessPrompt())));
        prompt.setBounds(x, footer, font.width(promptLabel) + 14, NumenStyle.CONTROL_H);
        saveButton = ui.add(new Button(saveLabel(), Button.Style.NORMAL, this::save));
        saveButton.setBounds(x + w - SAVE_W, footer, SAVE_W, NumenStyle.CONTROL_H);
        refreshSaveState();

        // 回执胶囊落在话筒那一行:盖掉的是本就可以晚点再看的提醒,不盖正文。
        ui.add(notice).setBounds(x, msgRow - 3, w, 15);
    }

    /** 要解释的行下面那一句小灰字的顶边。 */
    private static int noteBelow(int row) { return row + NumenStyle.CONTROL_H + 1; }

    /** 遮罩范围由宿主给——确认卡要盖住整个设置面板,不是只盖这个分区。 */
    public void setDimBounds(int dimX, int dimY, int dimW, int dimH) {
        this.dimX = dimX;
        this.dimY = dimY;
        this.dimW = dimW;
        this.dimH = dimH;
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
     * 的前提是回环,地址一放开就是谁都能操控主人的同伴。红字挂在局域网那一行,不弹全局警告。
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
        McpMode mcp = McpMode.instance();

        // 地址框:只读,像输入框一样有个框,右边就是复制——一眼看出"这条是拿去填给 AI 的"。
        int fieldW = w - COPY_W - 4;
        NumenStyle.box(s, x, endpointRow, fieldW, NumenStyle.CONTROL_H, c.inputBg(), c.inputBorder());
        s.drawText(TextClip.fit(s, mcp.endpoint(), fieldW - NumenStyle.FIELD_PAD * 2),
                x + NumenStyle.FIELD_PAD,
                NumenStyle.centerIn(endpointRow, NumenStyle.CONTROL_H, s.lineHeight()),
                c.textPrimary(), false);

        // 开着时抬头右边报一句接上没接上(等待接入 / 谁在用 · 多久前);关着时开关自己就说明了。
        if (mcp.enabled()) {
            String badge = statusLine(mcp);
            int bw = Minecraft.getInstance().font.width(badge) + 8;
            Badge.draw(s, badge, x + w - 30 - bw,
                    NumenStyle.centerIn(y, NumenStyle.HEADER_H, s.lineHeight()),
                    mcp.clientName() == null ? c.warning() : c.success(), 0xFFFFFFFF);
        }

        // 绑到所有网卡这件事本身会成功,只是降级——按自家判据是 warning 不是 danger。
        // 但令牌为空时它就变成"这次保存不该发生",那才是 danger。
        if (lanDraft) {
            boolean noToken = mcp.token().isBlank();
            s.drawText(t(noToken ? "numen.brain.lan_needs_token" : "numen.brain.lan_warn"),
                    x, noteBelow(lanRow), noToken ? c.danger() : c.warning(), false);
        }
        // 开着关着各是什么结果,当场写在开关下面——这一句比开关名更要紧。
        s.drawText(t(mcp.config().quietFallback()
                        ? "numen.brain.quiet_on" : "numen.brain.quiet_off"),
                x, noteBelow(quietRow), c.textMuted(), false);

        // 话筒那一行:起服失败最要紧,没有失败就说提示词那句提醒。
        String err = mcp.lastError();
        s.drawText(TextClip.fit(s, err == null ? t("numen.brain.prompt_warn")
                        : I18n.get("numen.brain.start_failed", err), w),
                x, msgRow, err == null ? c.textMuted() : c.danger(), false);

        if (tokenCopy != null) tokenCopy.setVisible(!mcp.token().isBlank());
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
