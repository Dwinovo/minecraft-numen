package com.dwinovo.numen.plugins.ftbquests;

import com.google.gson.JsonParseException;
import dev.ftb.mods.ftblibrary.util.TextComponentParser;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.TextUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * 任务书里的一个对象(章节、任务、任务项、奖励)叫什么——整个联动取名字只经这里。
 *
 * <p>FTB 自己的 {@code getTitle()} 只在客户端可靠:FTB Quests 的标题、图标这些方法标了 OnlyIn,Forge 的专用服务器把它们
 * 从类里删掉,服务端调到就是 {@code NoSuchMethodError}。所以按书在哪一侧分:客户端那本书交给 FTB 自己的 {@code getTitle()}
 * (它按玩家的语言给出完整的默认名);服务端那本书照 FTB 的规则在这里算,只用服务端存在的接口:
 * <ul>
 *   <li>写了原始标题:JSON 写法照 FTB 的顺序先试;其余写法(明文、{@code &} 颜色码、{@code {翻译键}})
 *       用 FTB Library 的 {@link TextComponentParser},{@code {翻译键}} 按服务端的语言表翻译——FTB 自己对这种写法
 *       走的是客户端的 {@code I18n},专用服务器上加载不了;</li>
 *   <li>没写:章节取 FTB 的默认名;任务取它第一个任务项的名字;物品任务项与物品奖励是物品名,数量大于 1 时前面带
 *       {@code 3x };其余任务项、奖励是它们类型的显示名({@code getDisplayName})。</li>
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
        return (raw.isEmpty() ? untitled(object) : serverText(raw)).getString();
    }

    /**
     * 客户端那本书里的一段原文(副标题、正文的一行)变成文字。FTB 自己的 {@code getSubtitle}/{@code getDescription}
     * 同样只在客户端存在,解析用的是它们背后的同一个 {@link TextUtils#parseRawText}。原文取自这本书的语言,
     * 所以主人客户端上读到的是主人的语言。
     */
    static String parse(String raw) {
        return TextUtils.parseRawText(raw).getString();
    }

    /**
     * FTB 的 {@code TextUtils.parseRawText} 先试 JSON、不成再当富文本,富文本那一支走客户端的 {@code I18n}。服务端照同一个顺序,
     * 富文本交给 FTB Library 的 {@link TextComponentParser},{@code {翻译键}} 按服务端的语言表翻译。
     */
    private static Component serverText(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") || trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                MutableComponent json = Component.Serializer.fromJson(trimmed);
                if (json != null) {
                    return json;
                }
            } catch (JsonParseException richText) {
                // 方括号、花括号开头结尾的富文本({翻译键} 就是),不是 JSON
            }
        }
        return TextComponentParser.parse(raw, key -> Component.translatable(key));
    }

    /** 没写标题时 FTB 的默认名,{@code getAltTitle} 在服务端的对应。 */
    private static Component untitled(QuestObjectBase object) {
        if (object instanceof Quest quest) {
            List<Task> tasks = quest.getTasksAsList();
            return tasks.isEmpty() ? unnamed() : Component.literal(of(tasks.get(0)));
        }
        if (object instanceof ItemTask task) {
            return counted(task.getMaxProgress(), task.getItemStack().getHoverName());
        }
        if (object instanceof ItemReward reward) {
            return counted(reward.getCount(), reward.getItem().getHoverName());
        }
        if (object instanceof Task task) {
            return task.getType().getDisplayName();
        }
        if (object instanceof Reward reward) {
            return reward.getType().getDisplayName();
        }
        if (object instanceof Chapter) {
            return unnamed();
        }
        throw new IllegalArgumentException("no name rule for " + object.getObjectType());
    }

    private static Component counted(long count, Component name) {
        return count > 1 ? Component.literal(count + "x ").append(name) : name;
    }

    private static Component unnamed() {
        return Component.translatable("ftbquests.unnamed");
    }
}
