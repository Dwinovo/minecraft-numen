package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.mixin.ChatComponentAccessor;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同伴对话在聊天框里的唯一出样口——聊天框是不开面板时的全量实时面:
 *
 * <pre>
 * 你 → sadasdas:帮我看看矿洞          (整行暗灰;语音带「(语音)」记号)
 * sadasdas:我看看…▌                    (流式行:边生成边长,完成后定格)
 * ⚙ sadasdas · goto                    (工具调用,最暗的状态行)
 * sadasdas:到了,矿洞在这边……          (回复全文,不折叠)
 * </pre>
 *
 * 统一格式统一配色,整体比真人聊天暗一层;没有尖括号,不冒充真人发言。
 * 瞬态提示(没能送达/先选人/没听清)不进聊天框,走 {@code TalkHint#flash}。
 *
 * <p>流式实现:原版聊天行不可编辑,靠"摘掉旧行 → 补一条更长的新行"
 * 模拟打字机(经 {@link ChatComponentAccessor});每个同伴最多一条在飞行,
 * 完成后由定格行接替。客户端主线程专用。
 */
public final class ChatLines {

    /** 自己发言:暗一层的灰(知道自己说了什么,回显只为日志完整)。 */
    private static final int OWN = 0xAAB0B8;
    /** 同伴正文:近白——正文必须一眼可读,"退后"交给名字配色与工具行。 */
    private static final int TEXT = 0xE8EBEF;
    /** 工具调用状态行:最暗一档——是动作记录,不是话。 */
    private static final int TOOL = 0x8A9099;

    /** 每个同伴的在飞流式行(摘行用的句柄)。 */
    private static final Map<UUID, GuiMessage> LIVE = new HashMap<>();

    private ChatLines() {}

    /**
     * 主人发出的一句(文字或语音),回显为暗灰字幕行;{@code queued} 时
     * 缀上排队标记——她正忙,这条在队列里等她收口。
     */
    public static void owner(String companionName, String text, boolean voice, boolean queued) {
        MutableComponent line = Component.literal(
                "你 → " + companionName + ":" + (voice ? "(语音) " : "") + text)
                .withColor(OWN);
        if (queued) {
            line.append(Component.literal("  (排队中,她忙完就看)").withColor(TOOL));
        }
        add(line);
    }

    /** 同伴的回复定格行:加粗着色名字 + 近白正文,全文显示不折叠。 */
    public static void companion(String companionName, String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return;
        }
        add(name(companionName).append(Component.literal(flat).withColor(TEXT)));
    }

    /** 工具调用状态行:⚙ 名字 · 工具名。 */
    public static void tool(String companionName, String toolName) {
        add(Component.literal("⚙ " + companionName + " · " + toolName).withColor(TOOL));
    }

    /**
     * 流式行更新:摘掉这只同伴的旧行,补上更长的新行(带光标记号)。
     * 新行永远落在聊天最新位,像正在打字。
     */
    public static void streaming(UUID companion, String companionName, String partial) {
        ChatComponent chat = Minecraft.getInstance().gui.getChat();
        ChatComponentAccessor acc = (ChatComponentAccessor) chat;
        removeLive(acc, companion);
        MutableComponent line = name(companionName)
                .append(Component.literal(partial).withColor(TEXT))
                .append(Component.literal("▌").withColor(OWN));
        chat.addMessage(line);
        List<GuiMessage> all = acc.numen$allMessages();
        if (!all.isEmpty()) {
            LIVE.put(companion, all.get(0));   // addMessage 把新行放在 0 位
        }
    }

    /** 流式收尾:摘掉在飞行(定格行由调用方随后补上)。 */
    public static void streamingDone(UUID companion) {
        ChatComponent chat = Minecraft.getInstance().gui.getChat();
        removeLive((ChatComponentAccessor) chat, companion);
    }

    /** 退出世界:清句柄(聊天框本身随会话销毁)。 */
    public static void clearLive() {
        LIVE.clear();
    }

    private static void removeLive(ChatComponentAccessor acc, UUID companion) {
        GuiMessage old = LIVE.remove(companion);
        if (old != null && acc.numen$allMessages().remove(old)) {
            acc.numen$refreshTrimmedMessages();
        }
    }

    /** 加粗的主题色名字前缀——同伴行的视觉锚点。 */
    private static MutableComponent name(String companionName) {
        return Component.literal(companionName + ":")
                .withColor(UiTheme.current().reply() & 0xFFFFFF)
                .withStyle(s -> s.withBold(true));
    }

    private static void add(Component line) {
        Minecraft.getInstance().gui.getChat().addMessage(line);
    }
}
