package com.dwinovo.numen.api.client.screen.settings;

import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;
import com.dwinovo.numen.api.client.ui.widget.Badge;
import com.dwinovo.numen.api.client.ui.widget.Button;
import com.dwinovo.numen.api.client.ui.widget.Disclosure;
import com.dwinovo.numen.api.client.ui.widget.Reveal;
import com.dwinovo.numen.api.client.ui.widget.InlineAlert;
import com.dwinovo.numen.api.client.ui.widget.ScrollBox;
import com.dwinovo.numen.api.client.ui.widget.TextField;
import com.dwinovo.numen.api.client.ui.widget.Toggle;
import com.dwinovo.numen.api.client.ui.widget.UiRoot;
import com.dwinovo.numen.api.mcp.server.McpConfig;
import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.client.agent.RemoteCompanions;
import com.dwinovo.numen.api.client.agent.RemoteLibrary;
import com.dwinovo.numen.api.client.agent.ServerLibraries;
import com.dwinovo.numen.api.mcp.server.McpAccessPrompt;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import com.dwinovo.numen.api.client.ui.mc.Sprites;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 外接大脑分区——一页说完,高级的收在倒三角后面。
 *
 * <h2>默认只有三件事</h2>
 * 地址、令牌怎么来、复制接入提示词。令牌不在这里:它们是主人在游戏里用 {@code /numen token} 发的,
 * 每张带着主人、作用域和身份,所以这一页只告诉人去哪发。
 * 端口、局域网、调用超时、不暴露的工具——这些收进「高级设置」,想细调的人点开就是。装不下就滚动({@link ScrollBox}),不再藏一层子页。
 *
 * <h2>两层:滚的与不滚的</h2>
 * 总开关那一行、话筒那一行、收尾的复制提示词按钮固定不动,其余的行在滚动层里。
 * 手绘的几条(地址与令牌的值、局域网警示)也在滚动层,所以要减掉 {@link ScrollBox#offset()}。
 *
 * <h2>版式</h2>
 * 照 Telegram 设置行({@link SettingsRows}):一行一项,左标签右值或右开关,组与组之间一道宽缝——
 * 总开关、接入信息、高级设置、拨了就生效的那一条,各是一组。
 *
 * <h2>草稿在字段上,不在控件上</h2>
 * 端口/局域网/超时/不暴露的工具是草稿,保存才落地;草稿记在本类字段上,折叠、展开、
 * 重建都不会把没保存的输入弄丢。保存成功后清成 null = 交回配置当真源。
 */
public final class BrainPanel {

    /** 图标钮的方块热区:图标 12 格,四周各留一格好点。 */
    private static final int ICON_BTN = Sprites.SIZE + 2;
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

    /** 高级设置开合的进度——状态在这儿,控件只是照着画;跨重建保留,过渡不被打断。 */
    private final Reveal advanced = new Reveal();
    /** 高级设置收着时内容到哪(相对滚动层顶)、全开时到哪;每帧按 {@link #advanced} 在两者之间取。 */
    private int baseH, fullH;
    /** 四份草稿;null = 跟着配置走(首次进来、以及保存成功之后)。 */
    private String portText, timeoutText, hiddenText;
    private Boolean lanOn;
    private TextField portField;
    private Button saveButton;
    /** 本页所有图标钮:图标不写字,悬停得说得出自己是干嘛的(宿主按命中取 tooltip)。 */
    private final List<Button> iconButtons = new ArrayList<>();
    private int x, y, w, h;

    /** 搭这一页时的宿主状态;{@code null} = 不是管理员(或服务端还没说)。配置变了才重搭,谁连着这类随时在变的不重搭。 */
    private ServerLibraries.McpServerState builtFor;
    private long lastAskMs;

    /** 手绘几条的基线(滚动层坐标,画的时候减 {@link ScrollBox#offset()})。 */
    private int endpointRow, tokenRow, lanRow;
    /** 话筒那一行的顶边(固定层):起服失败与回执胶囊落在这儿,平时空着。 */
    private int msgRow;

    public BrainPanel() {
        Minecraft mc = Minecraft.getInstance();
        for (UiRoot root : List.of(ui, fixedUi)) {
            root.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                    s -> mc.keyboardHandler.setClipboard(s));
            // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
            // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
            root.setInputFactory(com.dwinovo.numen.api.client.ui.mc.McTextInput.factory());
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
        iconButtons.clear();
        ServerLibraries.McpServerState st = ServerLibraries.mcpServer();
        builtFor = st;
        Font font = Minecraft.getInstance().font;
        if (st == null) {
            SettingsRows.label(fixedUi, t("numen.brain.admin_only"), x, y, w);
            return;
        }

        // ---- 固定层 ----
        // 这一页叫什么在面板抬头上;第一行是一条设置:左边说开关管什么,右边是开关
        SettingsRows.label(fixedUi, t("numen.brain.enable"), x, y, w - 140);
        // 开关回调只写配置,绝不在此重建——重建会 new 出滑块已在终点的新 Toggle,
        // 滑动动画连起步都来不及(真机教训:大脑区开关瞬时切换的病根)。
        // 开关本身就是"开着还是关着",这一行不另写一句;开着时开关左边报接上没接上(见 render)。
        Toggle tog = fixedUi.add(new Toggle(st.enabled(), on -> put("enabled", on)));
        tog.setBounds(x + w - 24, SettingsRows.controlY(y, 11), 22, 11);
        // 总开关自成一组,和下面接入要用的东西之间一道宽缝
        SettingsRows.gap(fixedUi, x, y + SettingsRows.ROW_H, w);

        int footer = NumenStyle.footerTop(y, h);
        msgRow = footer - 5 - 9;
        String promptLabel = t("numen.brain.copy_prompt");
        int promptW = font.width(promptLabel) + 14;
        Button prompt = fixedUi.add(new Button(promptLabel, Button.Style.ACCENT,
                () -> copy(accessPrompt())));
        prompt.setBounds(x, footer, promptW, NumenStyle.CONTROL_H);
        fixedUi.add(notice).setBounds(x, msgRow - 3, w, 15);

        // ---- 滚动层 ----
        // 一行一项,左标签右值(Telegram 设置行):地址、令牌的值是强调色的字,在 render 里右对齐画,
        // 紧挨着它们的是作用在它们身上的图标钮——值右对齐贴着图标,动作就贴着它作用的那个东西。
        int top = y + SettingsRows.ROW_H + SettingsRows.GAP_H;
        int ry = top;
        endpointRow = ry;
        SettingsRows.label(ui, t("numen.brain.endpoint"), x, ry, labelW());
        iconButton(Sprites.COPY, t("numen.brain.copy"), x + w - ICON_BTN, ry,
                () -> copy(state().endpoint()));
        ry += SettingsRows.ROW_H;

        tokenRow = ry;
        SettingsRows.label(ui, t("numen.brain.token"), x, ry, labelW());
        ry += SettingsRows.ROW_H;
        SettingsRows.gap(ui, x, ry, w);
        ry += SettingsRows.GAP_H;

        Disclosure adv = ui.add(new Disclosure(t("numen.brain.advanced"), advanced));
        adv.setBounds(x, SettingsRows.controlY(ry, NumenStyle.CONTROL_H), w, NumenStyle.CONTROL_H);
        baseH = ry + SettingsRows.ROW_H - top;

        // 高级设置的行一直摆着,开合只是露出多高(见 revealedH):行不重建,没保存的输入也不丢
        ry += SettingsRows.ROW_H;
        // 端口与「允许局域网」= 上面那条地址的两截。后者是 host 的人话面:关=127.0.0.1,
        // 开=0.0.0.0。玩家不必知道那五个字符,想绑具体网卡的高级用户改
        // config/numen/server/mcp_server.json —— 配置文件就是逃生舱。
        SettingsRows.label(ui, t("numen.brain.port"), x, ry, w - NUM_FIELD_W - 8);
        portText = portText != null ? portText : String.valueOf(st.port());
        portField = ui.add(new TextField(portText, v -> {
            portText = v;
            refreshSaveState();
        }).numeric());
        portField.setBounds(x + w - RIGHT_INSET - NUM_FIELD_W, SettingsRows.controlY(ry, NumenStyle.CONTROL_H),
                NUM_FIELD_W, NumenStyle.CONTROL_H);
        ry += SettingsRows.ROW_H;

        lanRow = ry;
        SettingsRows.label(ui, t("numen.brain.lan"), x, ry, w - 28);
        Toggle lan = ui.add(new Toggle(lanNow(), on -> {
            lanOn = on;
            refreshSaveState();
        }));
        lan.setBounds(x + w - 24, SettingsRows.controlY(ry, 11), 22, 11);
        ry = noteBelow(lanRow) + 9 + 3;

        SettingsRows.label(ui, t("numen.brain.timeout"), x, ry, w - NUM_FIELD_W - 8);
        timeoutText = timeoutText != null ? timeoutText
                : String.valueOf(st.callTimeoutSeconds());
        TextField timeoutField = ui.add(new TextField(timeoutText, v -> timeoutText = v)
                .numeric());
        timeoutField.setBounds(x + w - RIGHT_INSET - NUM_FIELD_W, SettingsRows.controlY(ry, NumenStyle.CONTROL_H),
                NUM_FIELD_W, NumenStyle.CONTROL_H);
        ry += SettingsRows.ROW_H;

        SettingsRows.label(ui, t("numen.brain.hidden_tools"), x, ry, HIDDEN_LABEL_W);
        hiddenText = hiddenText != null ? hiddenText : String.join(", ", st.hiddenTools());
        int hiddenW = w - RIGHT_INSET - HIDDEN_LABEL_W - 8;
        TextField hiddenField = ui.add(new TextField(hiddenText, v -> hiddenText = v)
                .placeholder(t("numen.brain.hidden_hint")));
        hiddenField.setBounds(x + w - RIGHT_INSET - hiddenW, SettingsRows.controlY(ry, NumenStyle.CONTROL_H),
                hiddenW, NumenStyle.CONTROL_H);
        ry += SettingsRows.ROW_H;

        saveButton = ui.add(new Button(saveLabel(), Button.Style.NORMAL, this::save));
        saveButton.setBounds(x + w - RIGHT_INSET - SAVE_W, SettingsRows.controlY(ry, NumenStyle.CONTROL_H),
                SAVE_W, NumenStyle.CONTROL_H);
        refreshSaveState();
        ry += SettingsRows.ROW_H;
        fullH = ry - top;

        // 视口到话筒那一行为止;内容装不下就滚,装得下连拇指都不画。
        // 视口左右各放宽一圈内边距:组间宽缝铺满整页宽,不能被裁在内容边上。
        scroll.measure(ui, x - NumenStyle.PAD, top, w + NumenStyle.PAD * 2, msgRow - 4 - top, revealedH());
    }

    /** 此刻露出的内容高:随高级设置的开合在 baseH 与 fullH 之间走。 */
    private int revealedH() {
        return baseH + Math.round((fullH - baseH) * advanced.value());
    }

    /** 地址、令牌两行标签的宽:值从右往左排,标签只要自己那几个字。 */
    private int labelW() {
        Font font = Minecraft.getInstance().font;
        return Math.max(font.width(t("numen.brain.endpoint")), font.width(t("numen.brain.token")));
    }

    /** 要解释的行下面那一句小灰字的顶边。 */
    private static int noteBelow(int row) { return SettingsRows.controlY(row, NumenStyle.CONTROL_H) + NumenStyle.CONTROL_H + 1; }

    private boolean lanNow() {
        return lanOn != null ? lanOn : state().lanExposed();
    }

    /** 端点改了且服务在跑 → 这次保存要重开服务,按钮如实说。 */
    private String saveLabel() {
        ServerLibraries.McpServerState cfg = state();
        boolean endpointChanged = portValue() != cfg.port() || lanNow() != cfg.lanExposed();
        return t(cfg.enabled() && endpointChanged
                ? "numen.brain.save_restart" : "numen.brain.save");
    }

    /** 草稿里的端口;空的或不成数 = -1(校验会拦下)。 */
    private int portValue() {
        if (portText == null) {
            return state().port();
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
     * <p>端口要在合法范围里。开了局域网不另拦:端点永远要令牌,只是明文 HTTP 的令牌会在网上明文传输,
     * 局域网那一行的警告说清并建议加反向代理。
     */
    private void refreshSaveState() {
        if (saveButton == null || portField == null) {
            return;
        }
        int port = portValue();
        boolean portOk = port >= 1 && port <= 65535;
        portField.setError(portOk ? null : t("numen.brain.port_range"));
        saveButton.setEnabled(portOk);
        saveButton.setLabel(saveLabel());
    }

    private void save() {
        ServerLibraries.McpServerState cfg = state();
        JsonArray hidden = new JsonArray();
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
        JsonObject body = new JsonObject();
        body.addProperty("host", lanNow() ? McpConfig.ANY_HOST : McpConfig.LOOPBACK);
        body.addProperty("port", portValue());
        body.addProperty("callTimeoutSeconds", Math.max(1, timeout));
        body.add("hiddenTools", hidden);
        RemoteCompanions.library().put(Libraries.MCP_SERVER, body, this::settleSave);
    }

    /** 拨一个开关,只改这一项。 */
    private void put(String key, boolean value) {
        JsonObject body = new JsonObject();
        body.addProperty(key, value);
        RemoteCompanions.library().put(Libraries.MCP_SERVER, body);
    }

    /** 服务端答了保存:办成,草稿作废、配置重新成为真源;没办成(最常见是端口被占),话挂回出错的那个框。 */
    private void settleSave(RemoteLibrary.Reply reply) {
        if (reply.ok()) {
            portText = timeoutText = hiddenText = null;
            lanOn = null;
            notice.show(InlineAlert.Severity.SUCCESS, t("numen.brain.saved"), 2_000);
        } else if (portField != null) {
            portField.setError(I18n.get("numen.brain.port_taken", portValue()));
        }
    }

    private static ServerLibraries.McpServerState state() {
        return ServerLibraries.mcpServer();
    }

    private static String accessPrompt() {
        ServerLibraries.McpServerState st = state();
        return McpAccessPrompt.build(st.endpoint());
    }

    // ---- 渲染 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        ServerLibraries.McpServerState mcp = state();
        // 状态页开着时每两秒向服务端要一次最新的(谁接进来了、多久前动过);配置变了才重搭
        if (nowMs - lastAskMs > 2_000) {
            lastAskMs = nowMs;
            RemoteCompanions.library().ask(Libraries.MCP_SERVER);
        }
        if (mcp == null || !mcp.sameConfig(builtFor)) {
            if (mcp != null || builtFor != null) build(x, y, w, h);
            mcp = state();
        }
        if (mcp == null) {
            fixedUi.render(s, c, mouseX, mouseY, nowMs);
            return;
        }
        if (advanced.step(nowMs)) scroll.resize(revealedH());

        scroll.beginClip(s);
        int dy = -scroll.offset();
        // 地址与令牌:行右侧的值(Telegram 设置行右侧是强调色的字),右对齐贴着各自的图标钮
        int valueX = x + labelW() + 8;
        rightValue(s, mcp.endpoint(), valueX, x + w - ICON_BTN - 4, endpointRow + dy, c.accent());
        rightValue(s, t("numen.brain.token_how"), valueX, x + w - 4, tokenRow + dy, c.textMuted());
        if (advanced.value() > 0f) {
            // 绑到所有网卡这件事本身会成功,只是降级:端点是明文 HTTP——按自家判据是 warning 不是 danger。
            if (lanNow()) {
                s.drawText(TextClip.fit(s, t("numen.brain.lan_warn"), w), x, noteBelow(lanRow) + dy,
                        c.warning(), false);
            }
        }
        ui.renderContent(s, c, mouseX, mouseY, nowMs);
        scroll.endClip(s);
        scroll.renderThumb(s, c);

        // 开着时开关左边报一句接上没接上(等待接入 / 谁在用 · 多久前);关着时开关自己就说明了。
        if (mcp.enabled()) {
            String badge = statusLine(mcp);
            int bw = Minecraft.getInstance().font.width(badge) + 8;
            Badge.draw(s, badge, x + w - 30 - bw,
                    SettingsRows.controlY(y, s.lineHeight()),
                    mcp.clientName() == null ? c.warning() : c.success(), 0xFFFFFFFF);
        }
        // 话筒那一行平时空着,只有起服失败时说话(回执胶囊也落在这儿)。
        String err = mcp.lastError();
        if (err != null && !mcp.enabled()) {
            s.drawText(TextClip.fit(s, I18n.get("numen.brain.start_failed", err), w),
                    x, msgRow, c.danger(), false);
        }

        ui.renderOverlayLayer(s, c, mouseX, mouseY, nowMs);
        fixedUi.render(s, c, mouseX, mouseY, nowMs);
    }

    /** 行右侧的值:在 {@code [left, right)} 里右对齐,放不下从尾部收口。 */
    private static void rightValue(IDrawSurface s, String text, int left, int right, int rowY, int color) {
        String shown = TextClip.fit(s, text, Math.max(0, right - left));
        s.drawText(shown, right - s.textWidth(shown), SettingsRows.controlY(rowY, s.lineHeight()), color, false);
    }

    /** 悬停在哪枚图标上要说的一句;宿主画完这一分区再把它画在最上面。 */
    public String tooltipAt(double mx, double my) {
        if (!scroll.inside(my)) {
            return null;   // 滚出视口的行被裁掉了,坐标上却还在
        }
        for (Button b : iconButtons) {
            if (b.enabled() && b.contains(mx, my)) return b.tooltip();
        }
        return null;
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
        // 表单里任何一个框里按回车 = 点保存(保存键不可用时回车不办事)
        if (keyCode == com.dwinovo.numen.api.client.ui.KeyCodes.ENTER && saveButton != null && saveButton.enabled()
                && ui.focusedWidget() instanceof com.dwinovo.numen.api.client.ui.widget.TextField) {
            save();
            return true;
        }
        return fixedUi.keyPressed(keyCode, modifiers) || ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return fixedUi.charTyped(ch) || ui.charTyped(ch);
    }

    // ---- 内部 ----

    /**
     * 一枚图标钮:GHOST 按钮(没有底也没有边)+ 一枚贴图 + 悬停说一句,在行内垂直居中。
     *
     * <p>动作写成 lambda 而不是 build 时就把值取出来:配置随时可变,捕获到的会是过期值。
     */
    private Button iconButton(ResourceLocation sprite, String tip, int bx, int by, Runnable action) {
        Button b = ui.add(new Button(tip, Button.Style.GHOST, action)
                .icon(Sprites.SIZE, Sprites.painter(sprite))
                .tooltip(tip));
        b.setBounds(bx, SettingsRows.controlY(by, ICON_BTN), ICON_BTN, ICON_BTN);
        iconButtons.add(b);
        return b;
    }

    private void copy(String text) {
        ui.copyToClipboard(text);
        notice.show(InlineAlert.Severity.SUCCESS, t("numen.brain.copied"), 1_500);
    }

    /** 开着时的一句:等谁来接,或者谁在用、多久前活跃过。关着不说——开关自己就说明了。 */
    private static String statusLine(ServerLibraries.McpServerState mcp) {
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
