package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.payload.ConsentRequestPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;

/**
 * 她的征询在聊天栏里的样子,给没装我们客户端的主人:一条消息写着她想做什么,下面是三个可点的按钮——[允许]、[以后都允许]、
 * [拒绝],点下去就是主人自己敲了 {@code /numen consent …}(底层就是那条命令,答复同走 {@link ConsentDesk#reply})。
 * 内容就是答复框画的那一份({@link ConsentRequestPayload},一堆一行:动词、对象、数量、为什么),语言键也是答复框用的同一批,
 * 所以两处说的是同一件事;服务端按主人客户端的语言把键翻成字再放行(见 {@code ServerLang})。
 */
public final class ConsentChat {

    /** 几件事一起问时最多列几件,其余计数。 */
    private static final int MAX_LISTED = 3;

    private ConsentChat() {}

    /** 一条挂着的请求。 */
    public static Component ask(String companion, ConsentRequestPayload request) {
        MutableComponent text = Component.literal("[" + companion + "] ");
        List<ConsentRequestPayload.Line> lines = request.lines();
        if (lines.size() == 1) {
            text.append(Component.translatable(Keys.CONSENT_ASK, thing(lines.get(0)))).append("\n")
                    .append(cause(lines.get(0)));
        } else {
            text.append(Component.translatable(Keys.CONSENT_ASK_MANY));
            for (ConsentRequestPayload.Line line : lines.subList(0, Math.min(MAX_LISTED, lines.size()))) {
                text.append("\n· ").append(thing(line)).append(" · ").append(cause(line));
            }
            if (lines.size() > MAX_LISTED) {
                text.append("\n+" + (lines.size() - MAX_LISTED));
            }
        }
        return text.append("\n")
                .append(button(Keys.CONSENT_ALLOW, ChatFormatting.GREEN, "allow", request.id(), null)).append(" ")
                .append(button(Keys.CONSENT_ALLOW_REMEMBER, ChatFormatting.AQUA, "remember", request.id(),
                        Component.literal(String.join("\n", request.remember()))))
                .append(" ").append(button(Keys.CONSENT_DENY, ChatFormatting.RED, "deny", request.id(), null));
    }

    /** 一条请求没等到他答复就撤了,为什么。 */
    public static Component withdrawn(String companion, ConsentDesk.Withdrawal why) {
        return Component.translatable(Keys.CONSENT_WITHDRAWN, companion, Component.translatable(why.key()));
    }

    /** 一件事本身:动词、对象(名字)、数量。 */
    private static Component thing(ConsentRequestPayload.Line line) {
        return Component.translatable(Keys.CONSENT_VERB_PREFIX + line.kind().word()).append(" ").append(line.name())
                .append(line.count() > 1 ? "×" + line.count() : "");
    }

    /** 为什么要问;撤不回的整条是警示色。 */
    private static Component cause(ConsentRequestPayload.Line line) {
        return line.cause().copy().withStyle(line.irreversible() ? ChatFormatting.RED : ChatFormatting.GRAY);
    }

    /** 一个按钮:点下去敲 {@code /numen consent <答复> <请求号>};悬停时没有特别要说的就露出那条命令。 */
    private static Component button(String label, ChatFormatting color, String answer, long id, Component hover) {
        String command = "/numen consent " + answer + " " + id;
        return Component.literal("[").append(Component.translatable(label)).append("]").withStyle(Style.EMPTY
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        hover == null || hover.getString().isEmpty() ? Component.literal(command) : hover)));
    }
}
