package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.widget.Badge;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.ConfirmDialog;
import com.dwinovo.numen.client.ui.widget.Disclosure;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.ScrollBox;
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
 * 外接大脑分区——一页说完,高级的收在倒三角后面。
 *
 * <h2>默认只有三件事</h2>
 * 地址、令牌、复制接入提示词。接一个外部 AI 只需要这三样,新手看到的就该只有这三样。
 * 端口、局域网、调用超时、不暴露的工具、外部 AI 不动手时她怎么办——这些收进「高级设置」,
 * 想细调的人点开就是。装不下就滚动({@link ScrollBox}),不再藏一层子页。
 *
 * <h2>两层:滚的与不滚的</h2>
 * 抬头(标题 + 主开关)、话筒那一行、收尾的复制提示词按钮固定不动,其余的行在滚动层里。
 * 手绘的几条(地址框、警示、说明、分隔线)也在滚动层,所以要减掉 {@link ScrollBox#offset()}。
 *
 * <h2>草稿在字段上,不在控件上</h2>
 * 端口/局域网/超时/不暴露的工具是草稿,保存才落地;草稿记在本类字段上,折叠、展开、
 * 重建都不会把没保存的输入弄丢。保存成功后清成 null = 交回配置当真源。
 */
public final class BrainPanel {

    private static final int COPY_W = 46;
    private static final int REGEN_W = 52;
    private static final int SAVE_W = 96;
    /** 高级设置里数值框的宽;所有行的右边沿都对齐在 {@code x + w - RIGHT_INSET}。 */
    private static final int NUM_FIELD_W = 60;
    private static final int RIGHT_INSET = 2;
    /** 「不暴露的工具」那一行:标签留这么宽,余下的都给输入框。 */
    private static final int HIDDEN_LABEL_W = 100;

    /** 会滚的行。 */
    private final UiRoot ui = new UiRoot();
    /** 不滚的:抬头、回执胶囊、收尾按钮。 */
    private final UiRoot fixedUi = new UiRoot();
    private final ScrollBox scroll = new ScrollBox();
    /** 页面级回执(已复制/已保存):跨 build 持久,重建不吞在途消息。 */
    private final InlineAlert notice = new InlineAlert();
    private final ConfirmDialog confirm = new ConfirmDialog();

    /** 高级设置开着没有——状态在这儿,控件只是照着画。 */
    private boolean advanced;
    /** 四份草稿;null = 跟着配置走(首次进来、以及保存成功之后)。 */
    private String portText, timeoutText, hiddenText;
    private Boolean lanOn;
    private TextField portField;
    private Button saveButton;
    private Button tokenCopy;
    private int x, y, w, h;
    private int dimX, dimY, dimW, dimH;

    /** 手绘几条的基线(滚动层坐标,画的时候减 {@link ScrollBox#offset()})。 */
    private int endpointRow, lanRow, ruleRow;
    /** 话筒那一行的顶边(固定层):起服失败与回执胶囊落在这儿,平时空着。 */
    private int msgRow;
    /** 收尾行的顶边与复制提示词按钮的宽:那句令牌提醒紧挨着它画。 */
    private int footerRow, promptW;

    public BrainPanel() {
        Minecraft mc = Minecraft.getInstance();
        for (UiRoot root : List.of(ui, fixedUi)) {
            root.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                    s -> mc.keyboardHandler.setClipboard(s));
            // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
            // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
            root.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
        }
    }

    public void build(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        ui.clear();
        fixedUi.clear();
        portField = null;
        saveButton = null;
        tokenCopy = null;
        McpMode mcp = McpMode.instance();
        McpConfig cfg = mcp.config();
        Font font = Minecraft.getInstance().font;

        // ---- 固定层 ----
        Label title = fixedUi.add(new Label(t("numen.brain.title"), Label.Role.PRIMARY));
        title.setBounds(x, NumenStyle.centerIn(y, NumenStyle.HEADER_H, 9), w - 140, 9);
        // 开关回调只写配置,绝不在此重建——重建会 new 出滑块已在终点的新 Toggle,
        // 滑动动画连起步都来不及(真机教训:大脑区开关瞬时切换的病根)。
        // 开关本身就是"开着还是关着",抬头不另写一句;开着时抬头右边报接上没接上(见 render)。
        Toggle tog = fixedUi.add(new Toggle(mcp.enabled(), McpMode.instance()::setEnabled));
        tog.setBounds(x + w - 24, NumenStyle.centerIn(y, NumenStyle.HEADER_H, 11), 22, 11);

        int footer = NumenStyle.footerTop(y, h);
        msgRow = footer - 5 - 9;
        String promptLabel = t("numen.brain.copy_prompt");
        footerRow = footer;
        promptW = font.width(promptLabel) + 14;
        Button prompt = fixedUi.add(new Button(promptLabel, Button.Style.ACCENT,
                () -> copy(McpMode.instance().accessPrompt())));
        prompt.setBounds(x, footer, promptW, NumenStyle.CONTROL_H);
        fixedUi.add(notice).setBounds(x, msgRow - 3, w, 15);

        // ---- 滚动层 ----
        int top = NumenStyle.bodyTop(y);
        int ry = top;
        // 地址是这一页的主角:带框的只读地址 + 复制,和 LM Studio 那类本地服务页同形。
        endpointRow = ry;
        copyButton(x + w - COPY_W, ry, () -> McpMode.instance().endpoint());
        ry += NumenStyle.ROW_PITCH;

        ui.add(new ValueRow(t("numen.brain.token"), this::tokenText)
                .dimWhen(() -> McpMode.instance().token().isBlank()))
                .setBounds(x, ry, w - REGEN_W - COPY_W - 8, NumenStyle.CONTROL_H);
        tokenCopy = copyButton(x + w - REGEN_W - 4 - COPY_W, ry, () -> McpMode.instance().token());
        Button tokenRegen = ui.add(new Button(t("numen.brain.regenerate"), Button.Style.NORMAL,
                this::askRegenerate));
        tokenRegen.setBounds(x + w - REGEN_W, ry, REGEN_W, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH + 4;

        Disclosure adv = ui.add(new Disclosure(t("numen.brain.advanced"), advanced, () -> {
            advanced = !advanced;
            scroll.toTop();     // 换了一份内容,上一份滚到哪儿了与这份无关
            build(this.x, this.y, this.w, this.h);
        }));
        adv.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        int bottom = ry + NumenStyle.CONTROL_H;

        if (advanced) {
            ry += NumenStyle.ROW_PITCH + 4;
            // 端口与「允许局域网」= 上面那条地址的两截。后者是 host 的人话面:关=127.0.0.1,
            // 开=0.0.0.0。玩家不必知道那五个字符,想绑具体网卡的高级用户改
            // config/numen/mcp_server.json —— 配置文件就是逃生舱。
            rowLabel(t("numen.brain.port"), ry, w - NUM_FIELD_W - 8);
            portText = portText != null ? portText : String.valueOf(cfg.port());
            portField = ui.add(new TextField(portText, v -> {
                portText = v;
                refreshSaveState();
            }).numeric());
            portField.setBounds(x + w - RIGHT_INSET - NUM_FIELD_W, ry, NUM_FIELD_W,
                    NumenStyle.CONTROL_H);
            ry += NumenStyle.ROW_PITCH;

            lanRow = ry;
            rowLabel(t("numen.brain.lan"), ry, w - 28);
            Toggle lan = ui.add(new Toggle(lanNow(), on -> {
                lanOn = on;
                refreshSaveState();
            }));
            lan.setBounds(x + w - 24, NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 11), 22, 11);
            ry = noteBelow(lanRow) + 9 + 5;

            rowLabel(t("numen.brain.timeout"), ry, w - NUM_FIELD_W - 8);
            timeoutText = timeoutText != null ? timeoutText
                    : String.valueOf(cfg.callTimeoutSeconds());
            TextField timeoutField = ui.add(new TextField(timeoutText, v -> timeoutText = v)
                    .numeric());
            timeoutField.setBounds(x + w - RIGHT_INSET - NUM_FIELD_W, ry, NUM_FIELD_W,
                    NumenStyle.CONTROL_H);
            ry += NumenStyle.ROW_PITCH;

            rowLabel(t("numen.brain.hidden_tools"), ry, HIDDEN_LABEL_W);
            hiddenText = hiddenText != null ? hiddenText : String.join(", ", cfg.hiddenTools());
            int hiddenW = w - RIGHT_INSET - HIDDEN_LABEL_W - 8;
            TextField hiddenField = ui.add(new TextField(hiddenText, v -> hiddenText = v)
                    .placeholder(t("numen.brain.hidden_hint")));
            hiddenField.setBounds(x + w - RIGHT_INSET - hiddenW, ry, hiddenW, NumenStyle.CONTROL_H);
            ry += NumenStyle.ROW_PITCH;

            saveButton = ui.add(new Button(saveLabel(), Button.Style.NORMAL, this::save));
            saveButton.setBounds(x + w - RIGHT_INSET - SAVE_W, ry, SAVE_W, NumenStyle.CONTROL_H);
            refreshSaveState();
            ry += NumenStyle.CONTROL_H + 8;

            // 上面那四行要点保存才算数,下面这个拨了就算——中间画一道线,别让人以为还得保存。
            ruleRow = ry;
            ry += 7;

            rowLabel(I18n.get("numen.brain.quiet_toggle", McpMode.QUIET_AFTER_MS / 60_000L),
                    ry, w - 28);
            Toggle quiet = ui.add(new Toggle(cfg.quietFallback(),
                    McpMode.instance()::setQuietFallback));
            quiet.setBounds(x + w - 24, NumenStyle.centerIn(ry, NumenStyle.CONTROL_H, 11), 22, 11);
            bottom = ry + NumenStyle.CONTROL_H;
        }

        // 视口到话筒那一行为止;内容装不下就滚,装得下连拇指都不画。
        scroll.measure(ui, x, top, w, msgRow - 4 - top, bottom - top);
    }

    /** 高级设置里每一行的标签:行内垂直居中,左边沿对齐。 */
    private void rowLabel(String text, int row, int labelW) {
        Label label = ui.add(new Label(text, Label.Role.MUTED));
        label.setBounds(x, NumenStyle.centerIn(row, NumenStyle.CONTROL_H, 9), labelW, 9);
    }

    /** 要解释的行下面那一句小灰字的顶边。 */
    private static int noteBelow(int row) { return row + NumenStyle.CONTROL_H + 1; }

    private boolean lanNow() {
        return lanOn != null ? lanOn : McpMode.instance().config().lanExposed();
    }

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
        boolean endpointChanged = portValue() != cfg.port() || lanNow() != cfg.lanExposed();
        return t(McpMode.instance().enabled() && endpointChanged
                ? "numen.brain.save_restart" : "numen.brain.save");
    }

    /** 草稿里的端口;空的或不成数 = -1(校验会拦下)。 */
    private int portValue() {
        if (portText == null) {
            return McpMode.instance().config().port();
        }
        try {
            return Integer.parseInt(portText.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
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
        int port = portValue();
        boolean portOk = port >= 1 && port <= 65535;
        portField.setError(portOk ? null : t("numen.brain.port_range"));
        boolean tokenOk = !lanNow() || !McpMode.instance().token().isBlank();
        saveButton.setEnabled(portOk && tokenOk);
        saveButton.setLabel(saveLabel());
    }

    private void save() {
        McpConfig cfg = McpMode.instance().config();
        List<String> hidden = new ArrayList<>();
        for (String piece : (hiddenText == null ? "" : hiddenText).split(",")) {
            String name = piece.strip();
            if (!name.isEmpty()) hidden.add(name);
        }
        int timeout = cfg.callTimeoutSeconds();
        if (timeoutText != null && !timeoutText.isBlank()) {
            try {
                timeout = Integer.parseInt(timeoutText.strip());
            } catch (NumberFormatException e) {
                timeout = cfg.callTimeoutSeconds();
            }
        }
        boolean ok = McpMode.instance().applySettings(
                lanNow() ? McpConfig.ANY_HOST : McpConfig.LOOPBACK,
                portValue(),
                Math.max(1, timeout),
                hidden,
                McpMode.instance().token());
        if (ok) {
            // 落地了,配置重新成为真源——草稿清空,下次 build 照配置填。
            portText = timeoutText = hiddenText = null;
            lanOn = null;
            notice.show(InlineAlert.Severity.SUCCESS, t("numen.brain.saved"), 2_000);
        } else {
            // 起服失败最常见的就是端口被占用——把话挂回出错的那个框,别飘在别处
            portField.setError(I18n.get("numen.brain.port_taken", portValue()));
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
        confirm.open(fixedUi, dimX, dimY, dimW, dimH,
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

        scroll.beginClip(s);
        int dy = -scroll.offset();
        // 地址框:只读,像输入框一样有个框,右边就是复制——一眼看出"这条是拿去填给 AI 的"。
        int fieldW = w - COPY_W - 4;
        NumenStyle.box(s, x, endpointRow + dy, fieldW, NumenStyle.CONTROL_H,
                c.inputBg(), c.inputBorder());
        s.drawText(TextClip.fit(s, mcp.endpoint(), fieldW - NumenStyle.FIELD_PAD * 2),
                x + NumenStyle.FIELD_PAD,
                NumenStyle.centerIn(endpointRow + dy, NumenStyle.CONTROL_H, s.lineHeight()),
                c.textPrimary(), false);
        if (advanced) {
            // 绑到所有网卡这件事本身会成功,只是降级——按自家判据是 warning 不是 danger。
            // 但令牌为空时它就变成"这次保存不该发生",那才是 danger。
            if (lanNow()) {
                boolean noToken = mcp.token().isBlank();
                s.drawText(t(noToken ? "numen.brain.lan_needs_token" : "numen.brain.lan_warn"),
                        x, noteBelow(lanRow) + dy, noToken ? c.danger() : c.warning(), false);
            }
            s.fillRect(x, ruleRow + dy, w, 1, c.divider());
        }
        ui.renderContent(s, c, mouseX, mouseY, nowMs);
        scroll.endClip(s);
        scroll.renderThumb(s, c);

        // 开着时抬头右边报一句接上没接上(等待接入 / 谁在用 · 多久前);关着时开关自己就说明了。
        if (mcp.enabled()) {
            String badge = statusLine(mcp);
            int bw = Minecraft.getInstance().font.width(badge) + 8;
            Badge.draw(s, badge, x + w - 30 - bw,
                    NumenStyle.centerIn(y, NumenStyle.HEADER_H, s.lineHeight()),
                    mcp.clientName() == null ? c.warning() : c.success(), 0xFFFFFFFF);
        }
        // 令牌就在提示词里,这句提醒紧挨着那个按钮——它是按钮的注脚,不是另起一行的公告。
        s.drawText(TextClip.fit(s, t("numen.brain.prompt_warn"), w - promptW - 8),
                x + promptW + 8,
                NumenStyle.centerIn(footerRow, NumenStyle.CONTROL_H, s.lineHeight()),
                c.textMuted(), false);
        // 话筒那一行平时空着,只有起服失败时说话(回执胶囊也落在这儿)。
        String err = mcp.lastError();
        if (err != null) {
            s.drawText(TextClip.fit(s, I18n.get("numen.brain.start_failed", err), w),
                    x, msgRow, c.danger(), false);
        }

        if (tokenCopy != null) tokenCopy.setVisible(!mcp.token().isBlank());
        ui.renderOverlayLayer(s, c, mouseX, mouseY, nowMs);
        fixedUi.render(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (fixedUi.hasOverlay()) return fixedUi.mouseClicked(mx, my, button);
        if (ui.hasOverlay()) return ui.mouseClicked(mx, my, button);
        if (fixedUi.mouseClicked(mx, my, button)) return true;
        // 视口外的行虽被裁掉,坐标上仍在——点击按可视区域裁决。
        return scroll.inside(my) && ui.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta) || scroll.scrolled(my, delta);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return fixedUi.keyPressed(keyCode, modifiers) || ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return fixedUi.charTyped(ch) || ui.charTyped(ch);
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
