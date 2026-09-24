package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.client.NumenKeys;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.hud.NumenHudToasts;
import com.dwinovo.numen.client.ui.NumenToasts;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.ConsentReplyPayload;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.platform.Services;

import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端这边的征询。挂着的每只同伴最多一条,照服务端推来的抄({@link ConsentRequestPayload}),撤回就收起。
 * 收起的不扔:征询是对话流里她的一条消息(Telegram 带内联按钮的那种,见 {@link ConsentMessage}),答完按钮停在
 * 选中的那个上、下面写着结果,所以每只同伴按到的先后留一串,断线才清。
 *
 * <p>"挂没挂着"只读这里:对话流里那条消息、输入行的提示栏、世界轮廓、面板侧栏的标记、派发器的兜底豁免
 * 都问 {@link #pending}。客户端主线程读写。
 */
public final class ConsentCards {

    /** 内联按钮里前三个各是一种答复。 */
    private static final ConsentAnswer.Decision[] DECISIONS = {
            ConsentAnswer.Decision.ALLOW_ONCE, ConsentAnswer.Decision.ALLOW_REMEMBER, ConsentAnswer.Decision.DENY};
    /** 第四个按钮:说一句再拒绝——点了输入框上方出提示栏,主人写的那句随拒绝送出。 */
    public static final int NOTE = DECISIONS.length;
    public static final int BUTTONS = NOTE + 1;

    private static final Map<UUID, Card> PENDING = new LinkedHashMap<>();
    private static final Map<UUID, List<Card>> HISTORY = new HashMap<>();

    private ConsentCards() {}

    /**
     * 一条征询,从她问出口到收起。"正在写那一句"也记在这里——对话流里的消息画它,输入行改它,两边看的是同一份。
     */
    public static final class Card {
        private final ConsentRequestPayload request;
        private final long arrivedAt;
        private final boolean irreversible;
        /** 点了"说一句再拒绝",输入框上方挂着提示栏。 */
        private boolean writing;
        /** 主人点的哪个键;{@code -1} = 还在等,或没等到主人答复就收了。 */
        private int chosen = -1;
        private String note = "";
        /** 没等到主人在这里答复就收了的原因;{@code null} = 不是这么收的,空串 = 主人在别处(命令)答了。 */
        private String gone;
        /** 收起的时刻;{@code 0} = 还挂着。 */
        private long settledAt;

        // ---- 画面上的过渡,归 ConsentMessage ----
        final float[] over = new float[BUTTONS];
        /** 按下去的那一圈(Telegram 的按钮波纹):哪个键、什么时候、按在键里哪一点。 */
        int pressedKey = -1;
        long pressedAt;
        int pressX, pressY;
        long frameAt;

        private Card(ConsentRequestPayload request, long arrivedAt) {
            this.request = request;
            this.arrivedAt = arrivedAt;
            this.irreversible = request.lines().stream().anyMatch(ConsentRequestPayload.Line::irreversible);
        }

        public ConsentRequestPayload request() {
            return request;
        }

        public UUID companion() {
            return request.companion();
        }

        /** 到的时刻(本机毫秒,和对话记录的时间戳同一个钟):消息按它排进时间线。 */
        public long arrivedAt() {
            return arrivedAt;
        }

        public boolean irreversible() {
            return irreversible;
        }

        public boolean waiting() {
            return settledAt == 0;
        }

        public long settledAt() {
            return settledAt;
        }

        public boolean writing() {
            return writing;
        }

        public int chosen() {
            return chosen;
        }

        public String note() {
            return note;
        }

        public String gone() {
            return gone;
        }

        /** 这个键写着什么。 */
        public static String label(int key) {
            return I18n.get(key == NOTE ? ModLanguageData.Keys.CONSENT_DENY_NOTE : switch (DECISIONS[key]) {
                case ALLOW_ONCE -> ModLanguageData.Keys.CONSENT_ALLOW;
                case ALLOW_REMEMBER -> ModLanguageData.Keys.CONSENT_ALLOW_REMEMBER;
                case DENY -> ModLanguageData.Keys.CONSENT_DENY;
            });
        }

        /** 点了一个键:前三个直接答;第四个去写那一句。 */
        public void press(int key) {
            if (!waiting() || key < 0 || key >= BUTTONS) return;
            if (key == NOTE) {
                writing = true;
            } else {
                reply(this, key, "");
            }
        }

        /** 收起提示栏,不答。 */
        public void stopWriting() {
            writing = false;
        }

        /** 提示栏挂着时主人发出去的那句:按拒绝连同这句送出。空的不算。 */
        public void denyWith(String said) {
            if (!waiting() || said == null || said.isBlank()) return;
            reply(this, NOTE, said);
        }

        private void settle() {
            writing = false;
            settledAt = System.currentTimeMillis();
        }
    }

    /**
     * 网络处理体:新的一条挂上(同一只同伴原来那条被它顶替);撤回就收起。她开始等主人时右上角弹一条
     * "xxx 请求你的同意,按 [Y] 答复"(同一只同伴换一条请求不再弹——她一直在等,不是又来了一个);主人没答就撤掉的
     * (超时、任务结束……)再弹一条为什么——那条消息下面也写着。
     */
    public static void accept(ConsentRequestPayload p) {
        UUID companion = p.companion();
        if (!p.withdrawn()) {
            Card old = PENDING.get(companion);
            if (old != null) {
                // 顶替时服务端直接推新的那条,不另推旧的撤回
                old.gone = ConsentDesk.SUPERSEDED;
                old.settle();
            } else {
                NumenHudToasts.push(NumenToasts.Severity.WARN, I18n.get(ModLanguageData.Keys.CONSENT_ASKING,
                        name(companion), NumenKeys.TALK_COMPANION.getTranslatedKeyMessage().getString()));
            }
            Card card = new Card(p, System.currentTimeMillis());
            PENDING.put(companion, card);
            HISTORY.computeIfAbsent(companion, k -> new ArrayList<>()).add(card);
            return;
        }
        Card card = PENDING.remove(companion);
        if (card == null) {
            return;   // 主人在这里答过了:答的那一刻已经收起
        }
        card.gone = p.withdrawnBecause();
        card.settle();
        if (!card.gone.isEmpty()) {
            NumenHudToasts.push(NumenToasts.Severity.WARN, I18n.get(ModLanguageData.Keys.CONSENT_WITHDRAWN,
                    name(companion), card.gone));
        }
    }

    /** 花名册里的名字;不在花名册里是 "?"。 */
    public static String name(UUID companion) {
        String name = NumenRoster.instance().name(companion);
        return name == null ? "?" : name;
    }

    /** 这只同伴挂着的那条;没有是 null。 */
    public static Card pending(UUID companion) {
        return companion == null ? null : PENDING.get(companion);
    }

    /** 最早挂上、还没答复的那条;没有是 null。 */
    public static Card first() {
        return PENDING.isEmpty() ? null : PENDING.values().iterator().next();
    }

    public static Collection<Card> all() {
        return PENDING.values();
    }

    /** 这只同伴这次连上以来问过的每一条(挂着的在最后),按到的先后。 */
    public static List<Card> history(UUID companion) {
        List<Card> cards = companion == null ? null : HISTORY.get(companion);
        return cards == null ? List.of() : cards;
    }

    /** 主人按了第 {@code key} 个键:答复发回服务端,这条当场收起——服务端随后推来的撤回找不到它,也就不再报原因。 */
    private static void reply(Card card, int key, String note) {
        String said = note.strip();
        if (said.length() > ConsentReplyPayload.MAX_NOTE_LENGTH) {
            said = said.substring(0, ConsentReplyPayload.MAX_NOTE_LENGTH);
        }
        ConsentAnswer.Decision decision = key == NOTE ? ConsentAnswer.Decision.DENY : DECISIONS[key];
        Services.NETWORK.sendToServer(new ConsentReplyPayload(card.companion(), card.request.id(), decision, said));
        card.chosen = key;
        card.note = said;
        card.settle();
        PENDING.remove(card.companion(), card);
    }

    /** 断线:上一个世界的征询一条都不留。 */
    public static void clear() {
        PENDING.clear();
        HISTORY.clear();
    }
}
