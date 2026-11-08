package com.dwinovo.numen.plugins.ftbquests;

import com.google.gson.JsonParseException;
import dev.ftb.mods.ftblibrary.util.TextComponentParser;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.TextUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * 任务书里的一个对象(章节、任务、任务项、奖励)叫什么,以及书里的原文怎么变成文字——整个联动取名字只经这里。
 *
 * <p>FTB 自己的 {@code getTitle()} 在服务端那本书上只给原始标题的字面:写成 {@code {翻译键}} 的标题得到的是
 * {@code {翻译键}} 本身。所以按书在哪一侧分:客户端那本书交给 FTB 自己的 {@code getTitle()}(按玩家的语言解析);
 * 服务端那本书在这里算:
 * <ul>
 *   <li>写了原始标题:JSON 写法照 FTB 的顺序先试;其余写法(明文、{@code &} 颜色码、{@code {翻译键}})用 FTB Library 的
 *       {@link TextComponentParser},{@code {翻译键}} 按服务端的语言表翻译——FTB 自己对这种写法走的是客户端的
 *       {@code I18n},专用服务器上加载不了;</li>
 *   <li>没写:任务取它第一个任务项的名字(FTB 的 {@code getAltTitle} 在这里取的是任务项的 {@code getTitle()},
 *       服务端上是原始标题的字面,所以自己走一遍);其余对象用 FTB 的 {@code getAltTitle()},它在服务端可用。</li>
 * </ul>
 */
final class QuestNames {

    private QuestNames() {}

    /** 这个对象在这本书里的名字。 */
    static String of(QuestObjectBase object) {
        if (!object.getQuestFile().isServerSide()) {
            return object.getTitle().getString();
        }
        String raw = object.getRawTitle();
        return (raw.isEmpty() ? untitled(object) : serverText(object, raw)).getString();
    }

    /**
     * 客户端那本书里的一段原文(副标题、正文的一行)变成文字。FTB 自己的 {@code getSubtitle}/{@code getDescription}
     * 同样只在客户端解析,用的是它们背后的同一个 {@link TextUtils#parseRawText}。原文取自这本书的语言,
     * 所以主人客户端上读到的是主人的语言。
     *
     * @param owner 这段原文所在的对象:解析要用它那本书的注册表
     */
    static String parse(QuestObjectBase owner, String raw) {
        return TextUtils.parseRawText(raw, owner.holderLookup()).getString();
    }

    /**
     * FTB 的 {@code TextUtils.parseRawText} 先试 JSON、不成再当富文本,富文本那一支走客户端的 {@code I18n}。服务端照同一个顺序,
     * 富文本交给 FTB Library 的 {@link TextComponentParser},{@code {翻译键}} 按服务端的语言表翻译。
     */
    private static Component serverText(QuestObjectBase owner, String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") || trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                MutableComponent json = Component.Serializer.fromJson(trimmed, owner.holderLookup());
                if (json != null) {
                    return json;
                }
            } catch (JsonParseException richText) {
                // 方括号、花括号开头结尾的富文本({翻译键} 就是),不是 JSON
            }
        }
        return TextComponentParser.parse(raw, key -> Component.translatable(key));
    }

    /** 没写标题时的默认名。 */
    private static Component untitled(QuestObjectBase object) {
        if (object instanceof Quest quest) {
            List<Task> tasks = quest.getTasksAsList();
            if (!tasks.isEmpty()) {
                return Component.literal(of(tasks.getFirst()));
            }
        }
        return object.getAltTitle();
    }
}
