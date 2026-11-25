package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.permission.Settings;
import com.dwinovo.numen.permission.Verb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 征询清单里的一条:一个等主人点头的动作。主人允许之后它就是一条任务期授权({@link #covers}),裁决把被它覆盖的"要问"放行;
 * 主人选"以后都允许"时,{@link #key} 这个名字记进主人给这种动作的名单。
 *
 * <p>同一件事两种说法:给模型的是英文短语({@code subject}、{@code cause}),给主人看的是物品图标与可翻译的
 * {@link Component}({@code icon}、{@code name}、{@code shownCause}),由主人的客户端按自己的语言显示——方块画成
 * 它的物品图标,起了名字的动物叫它的名字。两种说法在建这一条时从同一个动作、同一个裁决一起得出。
 *
 * @param kind       哪种动作
 * @param pos        方块动作的格子;实体动作与丢弃为 null——实体认的是那一只({@code entityId}),不是它脚下的格:
 *                   它走一步清单就不该变,否则同一件事会被当成新的征询重发
 * @param entityId   实体动作的实体 id;其余为 {@link #NO_ENTITY}
 * @param subject    方块、实体种类或物品的 id 路径({@code oak_log}、{@code wolf}、{@code diamond});指令是带 {@code /}
 *                   的整行({@code /setblock 1 64 2 stone})
 * @param icon       给主人看的图标:方块的物品形态、物品本身;实体、指令与没有物品形态的方块为 null
 * @param name       给主人看的名字(没有图标时显示):方块、物品的名字,实体种类的名字(记的是那一只时是它的名字),指令的整行
 * @param key        "以后都允许"记进名单的名字,{@link Action#key} 那一个;也是任务期授权认的"同一个名字"。
 *                   改设置的征询({@link #settingsEdit})不能记,为空串——她不能给自己发许可
 * @param cause      为什么要问:英文一句({@code belongs to the owner})
 * @param shownCause 同一句给主人看的
 * @param count      这一条说几件:丢几件;其余动作是 1
 */
public record ConsentItem(Verb kind, BlockPos pos, int entityId, String subject, Item icon, Component name,
                          String key, String cause, Component shownCause, int count) {

    public static final int NO_ENTITY = -1;

    public ConsentItem {
        pos = pos == null ? null : pos.immutable();
    }

    /**
     * 她要放宽主人设置的一条征询(调松一个档位、往名单里加一项):她自己不能做,主人点头才写。卡上写成她没法自己跑的那条命令,
     * 主人看得懂,也能自己敲。不能"以后都允许"。
     *
     * @param line 不带前导 {@code /} 的整行命令,如 {@code numen permission set Aria break allow}
     */
    public static ConsentItem settingsEdit(String line) {
        String shown = "/" + line;
        return new ConsentItem(Verb.COMMAND, null, NO_ENTITY, shown, null, Component.literal(shown), "",
                "changes the owner's permission settings",
                Component.translatable(ModLanguageData.Keys.PERMISSION_SETTINGS_EDIT), 1);
    }

    /** 点"以后都允许"会记下什么:这一条的动作与名字;不能记的为 null。 */
    public Settings.Entry remembering() {
        return key.isEmpty() ? null : new Settings.Entry(kind, key);
    }

    /**
     * 清单里的一堆:同一种动作、同一种东西、同一个理由。给模型的说法({@link #text})与给主人看的那一版
     * (动作、{@code head} 的图标或名字、数量、{@code head} 的 {@code shownCause})都从这一堆出。
     *
     * @param head  这一堆的第一条
     * @param count 这一堆合起来说几件(挖几块、丢几件)
     * @param cells 这一堆点得出的格子(实体与丢弃没有)
     */
    public record Group(ConsentItem head, int count, List<BlockPos> cells) {

        /** 给模型:{@code break 6 oak_log (1,64,2; …; +2 more): belongs to the owner}。 */
        public String text() {
            return head.kind.word() + ' ' + Listing.part(head.subject, count, cells) + ": " + head.cause;
        }
    }

    /**
     * 从一个被裁成"要问"的动作建一条({@link Gate#consentItem})。
     */
    static ConsentItem of(Action action, Verdict verdict) {
        String key = action.key();
        return switch (action.kind()) {
            case ATTACK, USE_ENTITY -> {
                var entity = action.entity();
                boolean thisOne = entity instanceof ServerPlayer || !Owners.of(entity).isEmpty();
                yield new ConsentItem(action.kind(), null, entity.getId(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), null,
                        thisOne ? entity.getName() : entity.getType().getDescription(), key, verdict.cause(),
                        verdict.shown(), 1);
            }
            case DROP, USE_ITEM -> {
                Subject subject = Subject.of(action);
                yield new ConsentItem(action.kind(), null, NO_ENTITY, subject.id, subject.icon, subject.name, key,
                        verdict.cause(), verdict.shown(), action.count());
            }
            default -> {
                Subject subject = Subject.of(action);
                yield new ConsentItem(action.kind(), action.pos(), NO_ENTITY, subject.id, subject.icon, subject.name,
                        key, verdict.cause(), verdict.shown(), 1);
            }
        };
    }

    /** 一批清单里"以后都允许"要记下的那几项,每项取先出现的那一条,去重、保持先后。 */
    public static List<ConsentItem> remembered(List<ConsentItem> items) {
        List<ConsentItem> out = new ArrayList<>();
        for (ConsentItem item : items) {
            if (item.remembering() != null && out.stream().noneMatch(seen -> seen.covers(item))) {
                out.add(item);
            }
        }
        return out;
    }

    /** {@link #remembered} 写进名单的样子。 */
    public static List<Settings.Entry> rememberedEntries(List<ConsentItem> items) {
        return remembered(items).stream().map(ConsentItem::remembering).toList();
    }

    /**
     * 选"以后都允许"会记下的那几项,写成英文一行({@code break minecraft:oak_log}):回执里交代给模型的、交给游戏之外的场所的是这一份。
     */
    public static List<String> rememberedRows(List<ConsentItem> items) {
        return rememberedEntries(items).stream().map(entry -> entry.verb().word() + ' ' + entry.name()).toList();
    }

    /**
     * 主人对这一条的同意覆盖不覆盖这个动作:同一种动作、同一个名字——于是挖一堆主人的原木只问一次,换成主人的箱子另问;
     * 点头打的是这只狼,别的狼另问;点头执行 {@code setblock},这一件活里的 {@code setblock} 都算。
     */
    public boolean covers(Action action) {
        return !key.isEmpty() && kind == action.kind() && action.names().contains(key);
    }

    /** 主人对这一条的同意覆盖不覆盖另一条征询:与 {@link #covers(Action)} 同一个判据。 */
    public boolean covers(ConsentItem other) {
        return !key.isEmpty() && kind == other.kind && key.equals(other.key);
    }

    /**
     * 清单,一堆一条:按"动作 + 对象 + 理由"归堆(实体按名字再分开),保持先出现的先列。
     * 答复框与回执都用这一份。
     */
    public static List<Group> listing(List<ConsentItem> items) {
        Map<String, List<ConsentItem>> groups = new LinkedHashMap<>();
        for (ConsentItem item : items) {
            String key = item.kind.word() + ' ' + item.subject + ' ' + item.name.getString() + ' ' + item.cause
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
            out.add(new Group(group.get(0), group.stream().mapToInt(ConsentItem::count).sum(), cells));
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

    /** 方块、物品与指令动作的对象:挖、右键看格子上的方块,放、丢、用看物品,指令就是那一整行。 */
    private record Subject(String id, Item icon, Component name) {

        static Subject of(Action action) {
            if (action.command() != null) {
                String line = "/" + action.command().line();
                return new Subject(line, null, Component.literal(line));
            }
            BlockState state = action.state();
            if (state != null && action.kind() != Verb.PLACE) {
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
