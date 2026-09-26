package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.client.command.Completion;
import com.dwinovo.numen.client.consent.ConsentCards;
import com.dwinovo.numen.client.consent.ConsentMessage;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 聊天输入行——NumenUI 版的瓤:一整条底色,左边输入框,右边一格图标(Telegram 的输入区)。
 * 那一格按状态换:有字是发送,空着是麦克风,她在忙时空着是叫停,录音中是停止录音——
 * 同一个位置只放一件事,换的时候旧的缩小淡出、新的放大淡入。输入框走 TextField(回车发送,Shift+回车换行),
 * 不画框线,底色是这一整条的;字多了随内容往上长(Telegram 的输入框),最多 {@link #MAX_LINES} 行,再多在框里滚,
 * 底边与右边那一格不动。
 *
 * <p>斜杠命令整个归这条输入行:补全弹层、面板类命令(/skills)在原位开面板、发送时
 * 的拦截("是 / 开头?在本地跑完,不往下走",见 {@code ChatCommands})。宿主只收到
 * 两样东西:真正要说给她的话({@link Host#onSend})和命令的回话
 * ({@link Host#onCommandReply})。G 面板和 Y 快捷对话用的是同一条输入行,差别只在
 * 宿主和带哪几颗键({@link Key}):快捷对话不带麦克风——快捷语音有自己的按住说话键,
 * 不搞两条语音路。
 *
 * <p>她在等主人点头的时候,征询是对话流里她的一条消息,下面挂一排内联按钮({@link ConsentMessage})。这条输入行
 * 不让位,按钮只用指针点(和 Telegram 一样);点了"说一句再拒绝"就在输入框上方挂一条提示栏(和引用栏同一条),
 * 这时发出去的那句就是拒绝的理由。挂没挂着只看
 * {@link ConsentCards},不各自判断。
 */
public final class ChatInputBar {

    /** 这条输入行右边那一格会轮到哪几种;G 面板全要,快捷对话不要麦克风(它有自己的按住说话键)。 */
    public enum Key { MIC, SEND, STOP }

    /** 右边那一格此刻是什么。 */
    private enum Act { SEND, MIC, RECORDING, STOP }


    /** 宿主回调面:说话/麦克风/叫停,以及"这几颗键此刻可不可按"。 */
    public interface Host {
        /** 主人真要说给她的话(斜杠命令不会走到这儿,已在输入行本地跑完)。 */
        void onSend(String text);

        /** 只有带 {@link Key#MIC} 的输入行会调。 */
        default void onMicToggle() {}

        void onAbort();

        boolean canAbort();

        /** 输入框占位文案(随麦克风状态变)。 */
        String hint();

        /**
         * 这条输入行对着的那位的大脑;null = 会话没有单一的主。斜杠命令能不能在这里用只看它:
         * null 时补全只给一行灰着的 {@link com.dwinovo.numen.client.command.ChatCommands#SOLO_ONLY},
         * 回车回的也是这一句,斜杠输入不会当话发出去。
         */
        com.dwinovo.numen.client.agent.EntityAgentLoop loop();

        /** 这条输入行对着的会话;`@` 补的是它里面的人。null = 没选。 */
        com.dwinovo.numen.agent.conversation.Conversation conversation();

        /** 斜杠命令跑完回给主人的话;null = 这条命令不吭声(或已在原位开了面板)。画在哪、留多久是宿主的事。 */
        void onCommandReply(String reply);

        /** 她等的那条征询收起了(主人答了、超时、任务结束)。在 {@link #tick} 里调。 */
        default void onConsentSettled() {}

        /** 这个会话里主人上一句说的话(输入框空着时按 ↑ 取回来改);没有是 null。 */
        default String lastSent() { return null; }

    }

    /** 右边那一格的宽。 */
    private static final int ACT_W = 22;
    /** 那一格换状态的过渡时长。 */
    private static final int ACT_SWAP_MS = 150;
    /** 输入框最多长到几行。 */
    private static final int MAX_LINES = 5;
    /** 输入框里 {@code /命令} 那一截的颜色。定死不跟主题走——它标的是"这是命令不是话"
     *  这件事,换主题不该让它变得像普通文字。 */
    private static final int CMD_COLOR = 0xFFA6AEE9;

    private final UiRoot ui = new UiRoot();
    private final Host host;
    /** 这条输入行带哪几颗键。 */
    private final Set<Key> wanted;

    private TextField field;
    private String draft = "";
    private boolean recording;
    /**
     * 引用回复:在回谁的哪一句;null = 没在回。输入行上面长出一条引用栏(Telegram 的回复栏),
     * 发出去时拼进正文(见 {@link com.dwinovo.numen.agent.conversation.Quote})。
     * 点了征询的"说一句再拒绝"时,同一个位置挂的是那条提示栏;两样同时只有一样(Telegram 的回复栏、编辑栏也互相顶替)。
     * {@code barShown} 是那条栏露出多高,按趋近走;收起的途中还要画,所以图标、抬头和那句另记一份。
     */
    private String quoteWho, quoteText;
    private String shownTitle = "", shownText = "";
    private ResourceLocation shownIcon = com.dwinovo.numen.client.ui.mc.Sprites.REPLY;
    private float barShown;
    private static final int BAR_H = 22;
    /** 上一帧的时刻:栏的长出、输入框的长高都按它算这一帧走多少。 */
    private long frameMs;
    /** 上一帧挂着提示栏的那条征询:刚点了"说一句再拒绝"(或换了一条)时把栏换成它的。 */
    private ConsentCards.Card noteShown;
    /** 上一刻这条输入行对着的那位挂着的征询:收起时告诉宿主。 */
    private ConsentCards.Card asked;
    /** 右边那一格:这一帧是什么、上一样是什么、什么时候换的(换的那 150ms 两样都画)。 */
    private Act act, prevAct;
    private long actSince;
    private int actX, actY, actH;

    /** 输入框自己的几何(弹层贴它上边长,面板占它的位);随内容往上长,{@code fieldY}、{@code fieldH} 每帧跟着变。 */
    private int fieldX, fieldY, fieldW, fieldH;
    /** 一行时的高与底边(往上长时底边不动),此刻露出多高(按趋近走)。 */
    private int rowH, fieldBottom;
    private float fieldShown;
    /** 开着的选择面板;非 null 时它<b>取代</b>输入框,键盘整个归它。 */
    /** 贴着输入框弹出来的那一层。装什么由命令决定(名单、读数卡…),见 Popup。 */
    private com.dwinovo.numen.client.ui.widget.Popup panel;
    /** 当前补全候选。空 = 不弹层。 */
    private List<Completion> candidates = List.of();
    private int selected;
    /** 主人按了 Esc 收起弹层;一改文字就复位——收的是"这次",不是这个功能。 */
    private boolean dismissed;

    public ChatInputBar(Host host, Set<Key> keys) {
        this.host = host;
        this.wanted = Set.copyOf(keys);
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        // 这是输入法辅助模组能认出这个框的前提——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
    }

    /** 输入框内容(切换同伴时宿主取走暂存,回来再 setText 放回)。 */
    public String text() {
        return field != null ? field.value() : draft;
    }

    public void setText(String text) {
        draft = text == null ? "" : text;
        if (field != null) field.setValue(draft);
        refreshCandidates();
    }

    /**
     * 这一行此刻占多高:{@link #build} 给的那一行,加上面长出来的引用栏或提示栏。底边不动、往上长;
     * 宿主按它排上面的东西。
     */
    public int height() {
        return fieldH + Math.round(barShown);
    }

    /** 这条输入行对着的那位挂着的征询;没有是 null。 */
    private ConsentCards.Card waiting() {
        var loop = host.loop();
        return loop == null ? null : ConsentCards.pending(loop.entityUuid());
    }

    /** 挂着的征询正等主人写那一句(点了"说一句再拒绝");没有是 null。 */
    private ConsentCards.Card noting() {
        ConsentCards.Card card = waiting();
        return card != null && card.writing() ? card : null;
    }

    /** 回这一句:输入行上面出引用栏(顶掉"说一句再拒绝"的提示栏),光标回到输入框。 */
    public void quote(String who, String text) {
        ConsentCards.Card note = noting();
        if (note != null) note.stopWriting();
        quoteWho = who;
        quoteText = text;
        shownTitle = Component.translatable("numen.chat.reply_to", who).getString();
        shownText = text.replace('\n', ' ');
        shownIcon = com.dwinovo.numen.client.ui.mc.Sprites.REPLY;
        if (field != null) ui.requestFocus(field);
    }

    /** 重建输入行时把引用带过去(已经露全的就直接露全,不再长一遍)。 */
    public record QuoteState(String who, String text) {}

    public QuoteState quoteState() {
        return quoteWho == null ? null : new QuoteState(quoteWho, quoteText);
    }

    public void restoreQuote(QuoteState q) {
        if (q == null) return;
        quote(q.who(), q.text());
        barShown = BAR_H;
    }

    private void cancelQuote() {
        quoteWho = null;
        quoteText = null;
    }

    /** 录音中:右边那一格是停止录音。 */
    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    /**
     * @param lead 左边让给宿主画的那一截(快捷对话的名字牌);输入框从它右边开始
     */
    public void build(int x, int y, int w, int h, int lead) {
        if (field != null) draft = field.value();   // 重建不丢已输入的文字
        ui.clear();
        x += lead;
        w -= lead;

        int inW = w - ACT_W;
        // 编辑交给一个真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        // 这是输入法辅助模组能认出这个框的前提——见 McTextInput。
        field = ui.add(new TextField(draft, v -> {
            draft = v;
            refreshCandidates();
        }).placeholder(host.hint())
                .bare(true)
                .maxLines(MAX_LINES)
                .highlight(this::highlights));
        field.setBounds(x, y, inW, h);
        fieldX = x;
        fieldY = y;
        fieldW = inW;
        fieldH = h;
        rowH = h;
        fieldBottom = y + h;
        fieldShown = h;
        actX = x + inW;
        actY = y;
        actH = h;

        ui.requestFocus(field);   // 开屏即可打字
        refreshCandidates();
        refreshEnablement();
    }

    /** 宿主每刻调:她等的那条征询收起了就告诉宿主。 */
    public void tick() {
        ConsentCards.Card card = waiting();
        if (asked != null && card == null) host.onConsentSettled();
        asked = card;
    }

    /**
     * 每帧同步可按性、占位文案与征询:叫停的可用性是活的;点了"说一句再拒绝"就把提示栏换上、光标给输入框。
     */
    public void refreshEnablement() {
        if (field == null) return;
        boolean paged = panel != null;
        // 面板在场时输入框让位(它就摆在输入框那格),旁边几颗键跟着停手——
        // 叫停除外:那是主人的急刹车,任何时候都得能按。
        field.setVisible(!paged);
        field.setEnabled(!paged);
        ConsentCards.Card card = waiting();
        ConsentCards.Card note = card != null && card.writing() ? card : null;
        if (note != null && note != noteShown) {
            cancelQuote();
            shownTitle = I18n.get(ModLanguageData.Keys.CONSENT_DENY_NOTE);
            shownText = ConsentMessage.summary(note);
            shownIcon = com.dwinovo.numen.client.ui.mc.Sprites.CANCEL;
            ui.requestFocus(field);
        }
        noteShown = note;
        field.placeholder(note != null
                ? I18n.get(ModLanguageData.Keys.CONSENT_NOTE_ROW, ConsentCards.name(note.companion()))
                : host.hint());
    }

    /** 收起输入框上方那条栏:引用或"说一句再拒绝"。 */
    private void closeBar() {
        ConsentCards.Card note = noting();
        if (note != null) note.stopWriting();
        cancelQuote();
    }

    private boolean barOpen() {
        return quoteWho != null || noting() != null;
    }

    // ---- 选择面板(取代输入框的那一层) ----

    /**
     * 打开一个面板。它摆在输入框那一格、底边对齐,<b>往上</b>长得更高——一次要看好几行,
     * 而下面没有地方。
     */
    public void openPopup(com.dwinovo.numen.client.ui.widget.Popup popup) {
        if (popup == null || field == null) return;
        panel = popup;
        int ph = Math.max(fieldH, panel.preferredHeight());
        panel.setBounds(fieldX, fieldY + fieldH - ph, fieldW, ph);
        candidates = List.of();   // 补全弹层让位:一次只有一个东西吃键盘
        refreshEnablement();
    }

    public boolean pageOpen() {
        return panel != null;
    }

    /** 关面板回到输入框。文字清空——刚才那串 {@code /skills} 已经用过了。 */
    private void closePage() {
        panel = null;
        setText("");
        refreshEnablement();
    }

    // ---- 宿主转发面 ----

    public void render(GuiGraphics g, int mouseX, int mouseY, long nowMs, NumenTheme.Colors c) {
        refreshEnablement();
        long now = System.currentTimeMillis();
        float dt = frameMs == 0 ? 0.016f : Math.min(0.1f, (now - frameMs) / 1000f);
        frameMs = now;
        grow(dt);
        IDrawSurface s = new McDrawSurface(g, Minecraft.getInstance().font);
        renderBar(g, mouseX, mouseY, c, dt);
        g.fill(fieldX, fieldY, actX + ACT_W, fieldY + fieldH, c.inputBg());   // 输入框和右边那一格同一条底
        ui.render(s, c, mouseX, mouseY, nowMs);
        renderAct(g, mouseX, mouseY, c);
        // 面板与弹层都最后画:它俩要压在对话流上面。同时只会有一个。
        if (panel != null) {
            panel.render(s, c, mouseX, mouseY, nowMs);
        } else if (popupOpen()) {
            CommandPopup.render(s, c, candidates, selected, fieldX, fieldY - 2 - Math.round(barShown), fieldW);
        }
    }

    /** 悬停的那一格的提示文案(宿主自行绘制 tooltip:定位与样式是宿主的事)。 */
    public String tooltipAt(double mx, double my) {
        if (act == null || !overAct(mx, my) || !actEnabled(act)) return null;
        return t(switch (act) {
            case SEND -> "numen.chat.send";
            case MIC, RECORDING -> "numen.chat.tip.mic";
            case STOP -> "numen.chat.tip.stop";
        });
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (barOpen() && overBarClose(mx, my)) {
            closeBar();
            return true;
        }
        if (act != null && overAct(mx, my)) {
            if (actEnabled(act)) {
                switch (act) {
                    case SEND -> send();
                    case MIC, RECORDING -> host.onMicToggle();
                    case STOP -> host.onAbort();
                }
            }
            return true;
        }
        return ui.mouseClicked(mx, my, button);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        // 面板在场:键盘整个归它,一个都不往下漏。Esc 是回输入框,不是关整个界面。
        if (panel != null) {
            if (keyCode == KeyCodes.ESCAPE) {
                closePage();
                return true;
            }
            panel.keyPressed(keyCode, modifiers);
            return true;
        }
        // 弹层在场时先归它:↑↓ 选、Tab 补/循环、Esc 收、回车先补再谈发送。
        if (popupOpen()) {
            switch (keyCode) {
                case KeyCodes.ESCAPE -> {
                    dismissed = true;
                    return true;
                }
                case KeyCodes.UP -> {
                    move(-1);
                    return true;
                }
                case KeyCodes.DOWN -> {
                    move(1);
                    return true;
                }
                case KeyCodes.TAB -> {
                    // 还没补上就补上;已经是它了就换下一个——同一颗键,两步都顺手。
                    if (!fillSelected()) {
                        move(1);
                        fillSelected();
                    }
                    return true;
                }
                case KeyCodes.ENTER -> {
                    if (KeyCodes.shift(modifiers)) break;   // Shift+回车是换行,归输入框
                    // 命令:回车 = 就要选中这条,现在执行;想接着打参数请按 Tab。
                    // @ 名字:回车只把名字填上——弹层关了(光标不在 @ 词上)回车才发,话还没说完。
                    boolean filled = fillSelected();
                    if (filled && !commandMode(field.value())) return true;
                    send();
                    return true;
                }
                default -> { }
            }
        }
        if (keyCode == KeyCodes.UP && field != null && field.isFocused() && field.value().isEmpty()) {
            // 空着按 ↑:把上一句拿回来改了再发(Telegram 按 ↑ 改上一条)
            String last = host.lastSent();
            if (last != null) {
                setText(last);
                return true;
            }
        }
        if (keyCode == KeyCodes.ESCAPE && barOpen()) {   // Esc 先收引用栏(或提示栏),再一次才关界面
            closeBar();
            return true;
        }
        if (keyCode == KeyCodes.ENTER && !KeyCodes.shift(modifiers) && field != null && field.isFocused()) {
            send();
            return true;
        }
        if (field != null && field.isFocused() && mentionKey(keyCode, modifiers)) {
            return true;
        }
        return ui.keyPressed(keyCode, modifiers);
    }

    /**
     * {@code @名字}是一个整体(Discord、微信里 @ 出来的都是一整块):退格/删除一下整个去掉,
     * 左右键一步跨过。段就是换色的那些段——同一份判断,亮的那块就是整块。
     */
    private boolean mentionKey(int keyCode, int modifiers) {
        if (KeyCodes.ctrl(modifiers)) return false;
        String text = field.value();
        if (commandMode(text)) return false;
        int cur = field.cursor();
        for (TextField.Span sp : highlights(text)) {
            switch (keyCode) {
                case KeyCodes.BACKSPACE -> {
                    if (cur != sp.end()) continue;
                    field.setValue(text.substring(0, sp.start()) + text.substring(sp.end()));
                    field.setCursor(sp.start());
                    return true;
                }
                case KeyCodes.DELETE -> {
                    if (cur != sp.start()) continue;
                    field.setValue(text.substring(0, sp.start()) + text.substring(sp.end()));
                    field.setCursor(sp.start());
                    return true;
                }
                case KeyCodes.LEFT -> {
                    if (cur != sp.end()) continue;
                    field.setCursor(sp.start());
                    return true;
                }
                case KeyCodes.RIGHT -> {
                    if (cur != sp.start()) continue;
                    field.setCursor(sp.end());
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }

    // ---- 补全 ----

    /** 弹层此刻该不该在。 */
    private boolean popupOpen() {
        return !dismissed && !candidates.isEmpty() && field != null && field.isFocused();
    }

    /**
     * 文字变了就重算候选,并把 Esc 的收起复位。两种来源、同一条管线:以 {@code /} 开头是命令
     * (问她的大脑),否则看光标左边是不是 {@code @} 开头的词(问会话的成员表)。
     */
    private void refreshCandidates() {
        dismissed = false;
        String text = field != null ? field.value() : draft;
        if (text == null) text = "";
        if (commandMode(text)) {
            var loop = host.loop();
            candidates = loop == null ? List.of(soloOnly(text))
                    : com.dwinovo.numen.client.command.ChatCommands.complete(loop, text);
        } else {
            var conv = host.conversation();
            candidates = conv == null ? List.of()
                    : com.dwinovo.numen.client.command.MentionCompletions.complete(text,
                            field != null ? field.cursor() : text.length(),
                            com.dwinovo.numen.client.agent.Conversations.instance().named(conv));
        }
        selected = firstEnabled();
    }

    /** 会话没有单一的主时补全弹层里唯一的一行:打的是哪条命令,灰着,理由和回车时回的是同一句。 */
    private static Completion soloOnly(String text) {
        String label = com.dwinovo.numen.client.command.ChatCommands.PREFIX
                + com.dwinovo.numen.client.command.ChatCommands.parse(text).name();
        return new Completion(text, label, com.dwinovo.numen.client.command.ChatCommands.SOLO_ONLY, false, false);
    }

    /** 这串输入是命令还是话——候选从哪来、框里哪段换色,都由它定。 */
    private static boolean commandMode(String text) {
        return com.dwinovo.numen.client.command.ChatCommands.isCommand(text);
    }

    /**
     * 输入框里换色的段:命令的首词换命令色(定死的),话里 {@code @} 到的名字换强调色——
     * 用的是路由那一份匹配({@link com.dwinovo.numen.agent.conversation.Mentions#spans}),
     * 亮的正好是发出去会醒的,和记录里画亮的一样。
     */
    private List<TextField.Span> highlights(String text) {
        if (commandMode(text)) {
            int end = 1;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
            return List.of(new TextField.Span(0, end, CMD_COLOR));
        }
        var conv = host.conversation();
        if (conv == null) return List.of();
        int color = com.dwinovo.numen.client.screen.UiTheme.current().cta();
        List<TextField.Span> out = new ArrayList<>();
        for (var sp : com.dwinovo.numen.agent.conversation.Mentions.spans(text,
                com.dwinovo.numen.client.agent.Conversations.instance().named(conv))) {
            out.add(new TextField.Span(sp.start(), sp.end(), color));
        }
        return out;
    }

    private int firstEnabled() {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).enabled()) return i;
        }
        return 0;
    }

    /** 选下一个可用的,到头绕回去;一个可用的都没有就不动。 */
    private void move(int dir) {
        int n = candidates.size();
        if (n == 0) return;
        for (int step = 1; step <= n; step++) {
            int i = Math.floorMod(selected + dir * step, n);
            if (candidates.get(i).enabled()) {
                selected = i;
                return;
            }
        }
    }

    /** 把选中项填进输入框。已经就是它了(或选不中)返回 false,让调用方决定下一步。 */
    private boolean fillSelected() {
        if (selected < 0 || selected >= candidates.size()) return false;
        Completion pick = candidates.get(selected);
        if (!pick.enabled() || pick.insert().equals(field.value())) return false;
        field.setValue(pick.insert());
        field.cursorToEnd();
        draft = pick.insert();
        refreshCandidates();
        return true;
    }

    public boolean charTyped(char ch) {
        // 面板在场时输入框是隐着的,打进去的字看不见也用不上——直接吞掉。
        if (panel != null) return true;
        return ui.charTyped(ch);
    }

    /** 输入框写满 {@link #MAX_LINES} 行以后,指针在它上面时滚轮在框里翻。 */
    public boolean mouseScrolled(double mx, double my, double delta) {
        return panel == null && ui.mouseScrolled(mx, my, delta);
    }

    /** 焦点给不给这条输入行:左栏搜索框在接字的时候,它得交出来。 */
    public void setFocused(boolean on) {
        if (field != null) ui.requestFocus(on ? field : null);
    }

    public boolean isFieldFocused() {
        return field != null && field.isFocused();
    }

    // ---- 内部 ----

    private void send() {
        if (field == null || panel != null) return;
        String text = field.value() == null ? "" : field.value().trim();
        if (text.isEmpty()) return;
        // 挂着"说一句再拒绝"的提示栏:这句是拒绝的理由,连同拒绝送给她,不当成一句话
        ConsentCards.Card note = noting();
        if (note != null) {
            note.denyWith(text);
            setText("");
            return;
        }
        // 斜杠命令是主人对客户端说的话:在本地跑完就结束,不往下走。所以它不过宿主的
        // 发言闸门——查技能、看清单这些事没有理由要求先配好 API key。
        if (com.dwinovo.numen.client.command.ChatCommands.isCommand(text)) {
            var loop = host.loop();
            if (loop == null) {
                setText("");
                host.onCommandReply(com.dwinovo.numen.client.command.ChatCommands.SOLO_ONLY);
                return;
            }
            // 面板类命令:多余的参数不理会——它要的不是参数,是一个能上下选的界面。
            var page = com.dwinovo.numen.client.command.ChatCommands.popupFor(loop, text);
            if (page != null) {
                openPopup(page);
                host.onCommandReply(null);
                return;
            }
            String reply = com.dwinovo.numen.client.command.ChatCommands.dispatch(loop, text);
            setText("");
            host.onCommandReply(reply);
            return;
        }
        if (quoteWho != null) {
            text = com.dwinovo.numen.agent.conversation.Quote.compose(quoteWho, quoteText, text);
            cancelQuote();
        }
        host.onSend(text);
    }

    /**
     * 输入行上面那条栏:从输入行上面长出来(和回到最新钮一样被裁着滑),一枚图标、一道竖线、抬头、那句,右端 × 收起。
     * 引用时是回复图标、"回复 谁"、被引的那句;"说一句再拒绝"时是拒绝图标、这个键的名字、她问的是什么。
     */
    private void renderBar(GuiGraphics g, int mouseX, int mouseY, NumenTheme.Colors c, float dt) {
        boolean open = barOpen();
        barShown = com.dwinovo.numen.client.ui.Anim.approach(barShown, open ? BAR_H : 0f, 18f, dt);
        if (barShown < 0.5f) return;
        var font = Minecraft.getInstance().font;
        int right = actX + ACT_W;
        int y0 = fieldY - BAR_H;
        g.enableScissor(fieldX, fieldY - Math.round(barShown), right, fieldY);
        g.fill(fieldX, y0, right, fieldY, c.inputBg());
        int size = com.dwinovo.numen.client.ui.mc.Sprites.SIZE;
        com.dwinovo.numen.client.ui.mc.Sprites.draw(g, shownIcon, fieldX + 5, y0 + (BAR_H - size) / 2, size, c.accent());
        int lx = fieldX + 24;
        g.fill(lx, y0 + 3, lx + 2, y0 + BAR_H - 3, c.accent());
        int room = actX - lx - 12;
        Nb.text(g, font, Nb.clip(font, shownTitle, room), lx + 6, y0 + 3, c.accent());
        Nb.text(g, font, Nb.clip(font, shownText, room), lx + 6, y0 + 12, c.textMuted());
        boolean hot = open && overBarClose(mouseX, mouseY);
        Nb.text(g, font, "×", actX + (ACT_W - font.width("×")) / 2, y0 + (BAR_H - 8) / 2,
                hot ? c.textPrimary() : c.textMuted());
        g.disableScissor();
    }

    /** 输入框随内容往上长:一行一行长到 {@link #MAX_LINES} 行,底边不动,高度按趋近走(上面的东西跟着让)。 */
    private void grow(float dt) {
        float target = rowH + (field.visibleLines() - 1) * field.linePitch();
        fieldShown = com.dwinovo.numen.client.ui.Anim.approach(fieldShown, target, 18f, dt);
        fieldH = Math.round(fieldShown);
        fieldY = fieldBottom - fieldH;
        field.setBounds(fieldX, fieldY, fieldW, fieldH);
    }

    private boolean overBarClose(double mx, double my) {
        return mx >= actX && mx < actX + ACT_W && my >= fieldY - BAR_H && my < fieldY;
    }

    /**
     * 右边那一格此刻是什么:录音中是停止录音;有字是发送;空着时她在忙是叫停,不忙是麦克风。
     * 面板开着时输入框让位,只剩叫停(主人的急刹车任何时候都得能按);什么都做不了时是灰着的发送。
     */
    private Act currentAct() {
        boolean paged = panel != null;
        if (wanted.contains(Key.MIC) && recording) return Act.RECORDING;
        if (!paged && !field.value().isBlank()) return Act.SEND;
        if (wanted.contains(Key.STOP) && host.canAbort()) return Act.STOP;
        if (!paged && wanted.contains(Key.MIC)) return Act.MIC;
        return Act.SEND;
    }

    private boolean actEnabled(Act a) {
        return switch (a) {
            case SEND -> panel == null && !field.value().isBlank();
            case MIC -> panel == null;
            case RECORDING -> true;
            case STOP -> host.canAbort();
        };
    }

    private boolean overAct(double mx, double my) {
        return mx >= actX && mx < actX + ACT_W && my >= actY && my < actY + actH;
    }

    /** 那一格:换状态时旧的缩小淡出、新的放大淡入(Telegram 发送键和麦克风互换的样子)。 */
    private void renderAct(GuiGraphics g, int mouseX, int mouseY, NumenTheme.Colors c) {
        long now = System.currentTimeMillis();
        Act a = currentAct();
        if (a != act) {
            prevAct = act;
            act = a;
            actSince = now;
        }
        float p = prevAct == null ? 1f : Math.min(1f, (now - actSince) / (float) ACT_SWAP_MS);
        if (p < 1f) drawAct(g, prevAct, 1f - p, false, c);
        drawAct(g, act, p, overAct(mouseX, mouseY), c);
    }

    private void drawAct(GuiGraphics g, Act a, float k, boolean hover, NumenTheme.Colors c) {
        if (k <= 0.02f) return;
        boolean on = actEnabled(a);
        ResourceLocation icon = switch (a) {
            case SEND -> com.dwinovo.numen.client.ui.mc.Sprites.SEND;
            case MIC -> com.dwinovo.numen.client.ui.mc.Sprites.MIC;
            case RECORDING, STOP -> com.dwinovo.numen.client.ui.mc.Sprites.STOP;
        };
        // 发送是强调色(Telegram 蓝色纸飞机);录音中是危险色,一眼知道在录;其余是淡字色,悬停提亮
        int rgb = !on ? c.textMuted()
                : a == Act.SEND ? c.accent()
                : a == Act.RECORDING ? c.danger()
                : hover ? c.textPrimary() : c.textMuted();
        int argb = (rgb & 0xFFFFFF) | (Math.round(255 * k * (on ? 1f : 0.5f)) << 24);
        int size = com.dwinovo.numen.client.ui.mc.Sprites.SIZE;
        float scale = 0.6f + 0.4f * k;
        g.pose().pushPose();
        g.pose().translate(actX + ACT_W / 2f, actY + actH / 2f, 0);
        g.pose().scale(scale, scale, 1f);
        com.dwinovo.numen.client.ui.mc.Sprites.draw(g, icon, -size / 2, -size / 2, size, argb);
        g.pose().popPose();
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
