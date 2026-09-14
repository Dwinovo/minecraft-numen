package com.dwinovo.numen.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
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
 * @param kind         动词
 * @param pos          方块动作的格子;实体动作是实体此刻站的格(卡片描轮廓用);丢弃为 null
 * @param entityId     实体动作的实体 id;其余为 {@link #NO_ENTITY}
 * @param subject      方块、实体种类或物品的 id 路径({@code oak_log}、{@code wolf}、{@code diamond})
 * @param rule         问的是哪一行规则的原文;没有任何一行覆盖时为空串
 * @param cause        为什么要问:那一行规则的自述({@code placed by a player})
 * @param irreversible 这件事撤不回(那一行规则的正项里有撤不回的信号)
 * @param remember     主人说"允许并记住"时存下的那一行 allow(推法见 {@link Rule#remembering})
 */
public record ConsentItem(Action.Kind kind, BlockPos pos, int entityId, String subject, String rule, String cause,
                          boolean irreversible, Rule remember) {

    public static final int NO_ENTITY = -1;

    public ConsentItem {
        pos = pos == null ? null : pos.immutable();
    }

    /** 清单正文的一行;{@code irreversible} 让卡片在这一行前面标出"撤不回"。 */
    public record Line(String text, boolean irreversible) {}

    /**
     * 从一个被裁成 ask 的动作建一条({@link Gate#consentItem} / {@link Gate#consentItemLive})。
     *
     * @param facts 裁决这个动作用的那一份事实——记住的规则按它推
     */
    static ConsentItem of(Action action, Verdict verdict, Facts facts) {
        String rule = verdict.rule() == null ? "" : verdict.rule().toString();
        boolean irreversible = verdict.rule() != null && verdict.rule().irreversible();
        Rule remember = Rule.remembering(action, verdict.rule(), facts);
        return switch (action.kind()) {
            case ATTACK, USE_ENTITY -> new ConsentItem(action.kind(), action.entity().blockPosition(),
                    action.entity().getId(), EntityType.getKey(action.entity().getType()).getPath(),
                    rule, verdict.cause(), irreversible, remember);
            case DROP -> new ConsentItem(action.kind(), null, NO_ENTITY, subjectOf(action), rule, verdict.cause(),
                    irreversible, remember);
            default -> new ConsentItem(action.kind(), action.pos(), NO_ENTITY, subjectOf(action), rule,
                    verdict.cause(), irreversible, remember);
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
     * 主人对这一条的同意覆盖不覆盖这个动作:同一个动词、同一行规则问出来的,而且是同一种东西——
     * 方块与物品认种类,实体认那一只。于是挖一堆主人放的原木只问一次,换成主人放的箱子另问;
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
            default -> subject.equals(subjectOf(action));
        };
    }

    /**
     * 清单正文,一堆一行:{@code break 6 oak_log (1,64,2; …; +2 more): placed by a player}。
     * 按"动词 + 对象 + 理由"归堆,保持先出现的先列。卡片与回执都用这一份。
     */
    public static List<Line> listing(List<ConsentItem> items) {
        Map<String, List<ConsentItem>> groups = new LinkedHashMap<>();
        for (ConsentItem item : items) {
            groups.computeIfAbsent(item.kind.verb() + ' ' + item.subject + ' ' + item.rule + ' ' + item.cause,
                    k -> new ArrayList<>()).add(item);
        }
        List<Line> lines = new ArrayList<>();
        for (List<ConsentItem> group : groups.values()) {
            ConsentItem head = group.get(0);
            List<BlockPos> cells = new ArrayList<>();
            for (ConsentItem item : group) {
                if (item.pos != null) {
                    cells.add(item.pos);
                }
            }
            lines.add(new Line(head.kind.verb() + ' ' + Listing.part(head.subject, group.size(), cells)
                    + ": " + head.cause, head.irreversible));
        }
        return lines;
    }

    /** 清单正文拼成一句(回执用)。 */
    public static String listingText(List<ConsentItem> items) {
        List<String> texts = new ArrayList<>();
        for (Line line : listing(items)) {
            texts.add(line.text());
        }
        return String.join("; ", texts);
    }

    private static String subjectOf(Action action) {
        BlockState state = action.state();
        if (state != null && action.kind() != Action.Kind.PLACE) {
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        }
        if (action.item() != null) {
            return BuiltInRegistries.ITEM.getKey(action.item()).getPath();
        }
        return "block";
    }
}
