package com.dwinovo.numen.core.pathing.settings;

import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.init.InitTag;
import com.dwinovo.numen.entity.CompanionRegistry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * 她愿意拿来垫路的方块——<b>每个同伴一份,落盘</b>。垫柱子、搭桥、铺台阶都从这里取料,
 * 反射(逃跑/脱困/摔落自救)走的也是这一份。
 *
 * <h2>为什么不是一份硬编码清单</h2>
 * "什么东西是垃圾"没法预先枚举:模组世界里她背包里堆的石头我们一个都不认识,而同一块
 * 圆石在矿洞里是垃圾、背到末地就是唯一的垫路料。所以清单归她自己管——模型看着背包和
 * 当下的处境用 {@code build scaffold_add} 这一系列命令增删,断料时的回执会把候选一并递到它面前。
 *
 * <h2>空表就是空表</h2>
 * 清空之后她一块都不垫——那是模型可以做的决定(背包里那些泥土留着盖房子,别拿去填坑),
 * 后果可见也可撤回。"没设过"由 {@link CompanionRegistry#DEFAULT_SCAFFOLD} 在读取时兜住,
 * 不靠空表兼职表达。
 *
 * <h2>出厂默认的选料判据</h2>
 * 遍地都是、没有功能、<b>放下去不会掉</b>——重力方块(沙/砂砾)一个不要,垫在半空会直接
 * 落下去。清单本身跟着同伴落盘,住在 {@link CompanionRegistry}。
 */
public final class ScaffoldMaterials {

    private ScaffoldMaterials() {}

    /** 出厂默认的 id 形式。 */
    public static List<String> factoryDefaultIds() {
        return CompanionRegistry.DEFAULT_SCAFFOLD;
    }

    /** 这个同伴实际认可的垫路料。清空过就是空表——她垫不了任何东西,如她所愿。 */
    public static List<Item> of(ServerPlayer player) {
        Set<Item> out = new LinkedHashSet<>();   // 标签之间会重叠,去重且保序
        for (String entry : storedIds(player)) {
            out.addAll(expand(entry));
        }
        return List.copyOf(out);
    }

    /** 同上,但给出 id 形式(工具回执用)。 */
    public static List<String> effectiveIds(ServerPlayer player) {
        return of(player).stream().map(ScaffoldMaterials::idOf).toList();
    }

    /** 存储里那份原样。同伴还没落盘(无头测试的空壳身体)时给出厂默认。 */
    public static List<String> storedIds(ServerPlayer player) {
        CompanionRegistry.Entry entry = entry(player);
        return entry == null ? CompanionRegistry.DEFAULT_SCAFFOLD : entry.scaffoldMaterials();
    }

    /**
     * 落盘这份清单。传空表 = 清空,她从此垫不了路;无效 id 直接丢掉,调用方回执报的是落盘后
     * 读回来的实际结果,不是它请求的那份。
     */
    public static void store(ServerPlayer player, List<String> ids) {
        MinecraftServer server = serverOf(player);
        if (server == null) {
            return;
        }
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = registry.find(player.getUUID());
        if (entry == null) {
            return;
        }
        registry.put(player.getUUID(), entry.withScaffoldMaterials(normalize(ids)));
    }

    /**
     * 去重、丢掉认不出的、保持给定顺序。<b>标签原样留着</b>不展开:她写 {@code #c:stones}
     * 是想说"这个包里的石头都算",展开存下来就冻在了此刻,数据包再改也跟不上。
     */
    public static List<String> normalize(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>(ids.size());
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String trimmed = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (InitTag.parseRef(Registries.ITEM, trimmed) != null) {
                out.add(trimmed);   // 认得出是标签形式就留着,内容留到用时再查
                continue;
            }
            Item item = parse(trimmed);
            if (item != null && item != Items.AIR) {
                out.add(idOf(item));
            }
        }
        return List.copyOf(out);
    }

    /** {@code stone} 与 {@code minecraft:stone} 都收;认不出返回 null。标签走 {@link #expand}。 */
    public static Item parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw.trim().toLowerCase(java.util.Locale.ROOT));
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /**
     * 一个条目展开成它代表的物品:{@code #ns:path} 是标签(当下的全部成员),否则是单个 id。
     * 空表示认不出来。
     *
     * <p>每次调用现查标签,所以 {@code /reload} 改了数据包下一次就生效——展开一次存起来
     * 就等于把标签冻在了那一刻。
     */
    private static List<Item> expand(String raw) {
        TagKey<Item> tag = InitTag.parseRef(Registries.ITEM, raw);
        if (tag != null) {
            List<Item> out = new ArrayList<>();
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                out.add(holder.value());
            }
            return out;
        }
        Item item = parse(raw);
        return item == null || item == Items.AIR ? List.of() : List.of(item);
    }

    // ==================== 从哪儿取料 ====================

    /**
     * 垫路料此刻从哪儿取。规划器问"有没有料可垫"({@link #available})、执行器切料去放、施工现场退回通用垫料,
     * 问的都是 {@link #source} 这一处——各写一份时,规划器会端出一条执行器选不出料的路,重新规划的输入
     * 分毫未变,那条路就一遍遍采纳即夭折。
     *
     * @param where 在哪儿:快捷栏;副手(主手换到 {@code slot},一个右键不放方块的格——空格或挖掘工具,
     *              右键才走副手);背包深处(用时搬进 7 号格)
     * @param slot  主手要切到的格;背包深处时是料所在的格
     */
    public record Source(Where where, int slot) {

        public enum Where { HOTBAR, OFFHAND, INVENTORY }

        /** 把这份料拿到手上:主手切到对应的格,背包深处的先和 7 号格对调。 */
        public void select(ServerPlayer player) {
            Inventory inventory = player.getInventory();
            if (where == Where.INVENTORY) {
                ItemStack moved = inventory.getItem(slot);
                inventory.setItem(slot, inventory.getItem(7));
                inventory.setItem(7, moved);
                inventory.selected = 7;
                return;
            }
            inventory.selected = slot;
        }
    }

    /**
     * 按清单顺序挑种类,每种先找快捷栏、再看副手、允许动背包({@link NavSettings#allowInventory})时再找
     * 背包深处;{@code usable} 再按拿哪只手放筛一道(施工现场要看这一格放不放得下)。没有返回 null。
     */
    public static Source source(ServerPlayer player, BiPredicate<ItemStack, InteractionHand> usable) {
        Inventory inventory = player.getInventory();
        boolean deep = NavSettings.get().allowInventory;
        for (Item item : of(player)) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.is(item) && usable.test(stack, InteractionHand.MAIN_HAND)) {
                    return new Source(Source.Where.HOTBAR, i);
                }
            }
            ItemStack offhand = player.getOffhandItem();
            if (offhand.is(item) && usable.test(offhand, InteractionHand.OFF_HAND)) {
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (stack.isEmpty()
                            || stack.getItem().components().has(net.minecraft.core.component.DataComponents.TOOL)) {
                        return new Source(Source.Where.OFFHAND, i);
                    }
                }
            }
            if (deep) {
                for (int i = 9; i < 36; i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (stack.is(item) && usable.test(stack, InteractionHand.MAIN_HAND)) {
                        return new Source(Source.Where.INVENTORY, i);
                    }
                }
            }
        }
        return null;
    }

    /** 不挑放在哪儿的那一问:见 {@link #source(ServerPlayer, BiPredicate)}。 */
    public static Source source(ServerPlayer player) {
        return source(player, (stack, hand) -> true);
    }

    /**
     * 规划器问的"有没有料可垫":手上找得到;或者是免耗材画像(创造)而清单不空——执行时 {@link #take} 会伸手
     * 进创造物品栏取清单里的料。清单空着就是她不垫,创造也一样。
     */
    public static boolean available(ServerPlayer player) {
        return source(player) != null || canConjure(player);
    }

    /**
     * 执行器要放的那一刻取料:手上有就用手上的;没有而 {@link #available} 认了能变出来,就取一组清单里排
     * 第一的料放进背包——原版创造玩家放置也得先手持方块,真人从创造栏抓,假玩家没有那个 GUI,这里就是
     * 那只手。取不到(背包满)返回 null。
     */
    public static Source take(ServerPlayer player) {
        Source source = source(player);
        if (source != null || !canConjure(player)) {
            return source;
        }
        return player.getInventory().add(new ItemStack(of(player).get(0), 64)) ? source(player) : null;
    }

    private static boolean canConjure(ServerPlayer player) {
        return WorkProfile.of(player).freeMaterials() && !of(player).isEmpty();
    }

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /**
     * 走不通时的那句话——只在<b>确实一件垫路料都没有</b>时给出,否则返回 null(路走不通是别的
     * 原因,别把模型往岔路上引)。
     *
     * <p>光说"没料"没用:清单是她自己定的,背包里那 184 块模组花岗岩她也看不见。所以两样都端
     * 出来,让"清单漏了"从死路变成一个能自己走出去的岔路口。
     */
    public static String shortageAdvice(ServerPlayer player) {
        List<Item> accepted = of(player);
        if (accepted.isEmpty()) {
            return " Your scaffolding list is EMPTY, so pathfinding may not place a single block —"
                    + " no pillaring, bridging or stepping up. That was your own call; put blocks"
                    + " back with build scaffold_add if this route needs them.";
        }
        var inv = player.getInventory();
        Map<String, Integer> spare = new LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (accepted.contains(stack.getItem())) {
                return null;   // 有料,走不通是别的原因
            }
            if (stack.getItem() instanceof BlockItem) {
                spare.merge(idOf(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        String carrying = spare.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(6)
                .map(e -> e.getKey() + "×" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        StringBuilder out = new StringBuilder(" You are carrying NONE of your scaffolding materials (")
                .append(String.join(", ", effectiveIds(player)))
                .append("), so pathfinding could not pillar, bridge or step anywhere.");
        if (carrying.isEmpty()) {
            return out.append(" Mine some of those blocks first.").toString();
        }
        return out.append(" You ARE carrying: ").append(carrying)
                .append(". Add what you are willing to spend with build scaffold_add, or go mine "
                        + "something already on the list.").toString();
    }

    /** 没有世界的空壳身体(无头测试、构造中途)一律当"没定制过"——回落出厂默认,不炸。 */
    private static MinecraftServer serverOf(ServerPlayer player) {
        return player == null || player.level() == null ? null : player.level().getServer();
    }

    private static CompanionRegistry.Entry entry(ServerPlayer player) {
        MinecraftServer server = serverOf(player);
        return server == null ? null : CompanionRegistry.get(server).find(player.getUUID());
    }
}
