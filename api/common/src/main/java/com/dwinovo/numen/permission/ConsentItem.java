package com.dwinovo.numen.permission;

import com.dwinovo.numen.data.ModLanguageData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 征询清单里的一条:一个等主人点头的动作。主人允许之后它就是一条任务期授权
 * ({@link #covers}),裁决把被它覆盖的 ask 放行;主人选"允许并记住"时,{@link #remember} 那一行写进
 * 主人的 allow 表。
 *
 * <p>同一件事两种说法:给模型的是英文短语({@code subject}、{@code cause}),给主人看的是物品图标与可翻译的
 * {@link Component}({@code icon}、{@code name}、{@code shownCause}),由主人的客户端按自己的语言显示——方块画成
 * 它的物品图标,起了名字的动物叫它的名字。两种说法在建这一条时从同一个动作、同一行规则一起得出。
 *
 * @param kind         动词
 * @param pos          方块动作的格子;实体动作与丢弃为 null——实体认的是那一只({@code entityId}),不是它脚下的格:
 *                     它走一步清单就不该变,否则同一件事会被当成新的征询重发
 * @param entityId     实体动作的实体 id;其余为 {@link #NO_ENTITY}
 * @param subject      方块、实体种类或物品的 id 路径({@code oak_log}、{@code wolf}、{@code diamond});指令是带 {@code /}
 *                     的整行({@code /setblock 1 64 2 stone})
 * @param icon         给主人看的图标:方块的物品形态、物品本身;实体、指令与没有物品形态的方块为 null
 * @param name         给主人看的名字(没有图标时显示):方块、物品的名字,实体的名字(起了名的就是那个名字),
 *                     指令的整行
 * @param rule         问的是哪一行规则的原文;没有任何一行覆盖时为空串
 * @param cause        为什么要问:那一行规则的自述({@code placed by a player})
 * @param shownCause   给主人看的为什么要问:命中那行规则就这个动作说的那一版({@link Rule#shown}),哪一行都没说到就说没有规则
 * @param irreversible 这件事撤不回(那一行规则的正项里有撤不回的信号)
 * @param remember     主人说"允许并记住"时存下的那一行 allow(推法见 {@link Rule#remembering})
 */
public record ConsentItem(Action.Kind kind, BlockPos pos, int entityId, String subject, Item icon, Component name,
                          String rule, String cause, Component shownCause, boolean irreversible, Rule remember) {

    public static final int NO_ENTITY = -1;

    public ConsentItem {
        pos = pos == null ? null : pos.immutable();
    }

    /**
     * 清单里的一堆:同一个动词、同一种东西、同一个理由。给模型的说法({@link #text})与给主人看的那一版
     * (动词、{@code head} 的图标或名字、数量、{@code head} 的 {@code shownCause})都从这一堆出。
     *
     * @param head  这一堆的第一条
     * @param count 这一堆有几条
     * @param cells 这一堆点得出的格子(实体与丢弃没有)
     */
    public record Group(ConsentItem head, int count, List<BlockPos> cells) {

        public boolean irreversible() {
            return head.irreversible;
        }

        /** 给模型:{@code break 6 oak_log (1,64,2; …; +2 more): placed by a player}。 */
        public String text() {
            return head.kind.verb() + ' ' + Listing.part(head.subject, count, cells) + ": " + head.cause;
        }
    }

    /**
     * 从一个被裁成 ask 的动作建一条({@link Gate#consentItem} / {@link Gate#consentItemLive})。
     *
     * @param facts 裁决这个动作用的那一份事实——记住的规则按它推
     */
    static ConsentItem of(Action action, Verdict verdict, Facts facts) {
        String rule = verdict.rule() == null ? "" : verdict.rule().toString();
        boolean irreversible = verdict.rule() != null && verdict.rule().irreversible();
        Rule remember = Rule.remembering(action, verdict.rule(), facts);
        Component why = verdict.rule() != null ? verdict.rule().shown(action, facts)
                : Component.translatable(ModLanguageData.Keys.PERMISSION_UNCOVERED);
        return switch (action.kind()) {
            case ATTACK, USE_ENTITY -> new ConsentItem(action.kind(), null, action.entity().getId(),
                    EntityType.getKey(action.entity().getType()).getPath(), null, action.entity().getName(), rule,
                    verdict.cause(), why, irreversible, remember);
            case DROP -> {
                Subject subject = Subject.of(action);
                yield new ConsentItem(action.kind(), null, NO_ENTITY, subject.id, subject.icon, subject.name, rule,
                        verdict.cause(), why, irreversible, remember);
            }
            default -> {
                Subject subject = Subject.of(action);
                yield new ConsentItem(action.kind(), action.pos(), NO_ENTITY, subject.id, subject.icon, subject.name,
                        rule, verdict.cause(), why, irreversible, remember);
            }
        };
    }

    /** 一批清单要记住的规则,去重、保持先后。 */
    public static List<Rule> remembered(List<ConsentItem> items) {
        List<Rule> rules = new ArrayList<>();
        for (ConsentItem item : items) {
            if (!rules.contains(item.remember)) {
                rules.add(item.remember);
            }
        }
        return rules;
    }

    /**
     * 选"允许并记住"会写进主人 allow 表的那几行,写成表里的样子({@code allow break(placed & oak_log)})。
     * 主人在选项上看到的、回执里交代给模型的,都是这一份。
     */
    public static List<String> rememberedRows(List<ConsentItem> items) {
        List<String> rows = new ArrayList<>();
        for (Rule rule : remembered(items)) {
            rows.add("allow " + rule);
        }
        return rows;
    }

    /**
     * 主人对这一条的同意覆盖不覆盖这个动作:同一个动词、同一行规则问出来的,而且是同一种东西——
     * 方块与物品认种类,实体认那一只,指令认整行。于是挖一堆主人放的原木只问一次,换成主人放的箱子另问;
     * 点头打的是这只狼,别的狼另问。
     *
     * @param hit 这个动作此刻命中的那行 ask 规则;没有任何一行覆盖时为 null
     */
    public boolean covers(Action action, Rule hit) {
        if (action.kind() != kind || !rule.equals(hit == null ? "" : hit.toString())) {
            return false;
        }
        return switch (kind) {
            case ATTACK, USE_ENTITY -> action.entity() != null && action.entity().getId() == entityId;
            default -> subject.equals(Subject.of(action).id);
        };
    }

    /**
     * 清单,一堆一条:按"动词 + 对象 + 理由"归堆(实体按名字、玩家放的按谁放的再分开),保持先出现的先列。
     * 答复框与回执都用这一份。
     */
    public static List<Group> listing(List<ConsentItem> items) {
        Map<String, List<ConsentItem>> groups = new LinkedHashMap<>();
        for (ConsentItem item : items) {
            String key = item.kind.verb() + ' ' + item.subject + ' ' + item.name.getString() + ' ' + item.rule
                    + ' ' + item.shownCause.getString();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        List<Group> out = new ArrayList<>();
        for (List<ConsentItem> group : groups.values()) {
            List<BlockPos> cells = new ArrayList<>();
            for (ConsentItem item : group) {
                if (item.pos != null) {
                    cells.add(item.pos);
                }
            }
            out.add(new Group(group.get(0), group.size(), cells));
        }
        return out;
    }

    /** 清单拼成一句给模型(回执用)。 */
    public static String listingText(List<ConsentItem> items) {
        List<String> texts = new ArrayList<>();
        for (Group group : listing(items)) {
            texts.add(group.text());
        }
        return String.join("; ", texts);
    }

    /** 方块、物品与指令动作的对象:挖、右键、拿看格子上的方块,放、丢看物品,指令就是那一整行。 */
    private record Subject(String id, Item icon, Component name) {

        static Subject of(Action action) {
            if (action.command() != null) {
                String line = "/" + action.command().line();
                return new Subject(line, null, Component.literal(line));
            }
            BlockState state = action.state();
            if (state != null && action.kind() != Action.Kind.PLACE) {
                Item form = state.getBlock().asItem();
                return new Subject(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(),
                        form == Items.AIR ? null : form, state.getBlock().getName());
            }
            if (action.item() != null) {
                return new Subject(BuiltInRegistries.ITEM.getKey(action.item()).getPath(), action.item(),
                        action.item().getDescription());
            }
            return new Subject("block", null, Component.translatable(ModLanguageData.Keys.PERMISSION_A_BLOCK));
        }
    }
}
