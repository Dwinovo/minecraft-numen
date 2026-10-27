package com.dwinovo.numen.build;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 落位前把方块状态<b>归一</b>,以及判定一格能不能建——"文本 → 要放的方块"的唯一收口。
 *
 * <p>归一的判据只有一条:<b>不是作者定的,就不该照抄</b>。一串格可以来自某个世界某一刻的快照,里面混着大量世界自己算出来的
 * 东西——作物长到第几节、方块含不含水、活塞伸没伸出去、堆肥桶攒了多少、锅里装的什么。照字面摆下去,就会凭空长出一片熟麦子、
 * 凭空造出水来、摆出一口装着岩浆的锅。{@link BuildValidity} 那张"作者属性"白名单是同一条判据的另一面:它管比对时忽略什么,
 * 这里管落位前清掉什么。
 */
public final class BuildStates {

    private BuildStates() {}

    /** 一格要放的方块,读好了:方块状态、记账用的物品、回执里的名字。 */
    public record Resolved(BlockState state, net.minecraft.world.item.Item item, String label) {}

    /**
     * 读一格要放的方块,写法和原版 {@code /setblock} 一字不差({@code oak_stairs[facing=east,half=top]}),由原版的
     * {@link net.minecraft.commands.arguments.blocks.BlockStateParser} 读——朝向、上下半、台阶三态都是它的事,不另立一套键名,
     * "文本 → 方块状态"全仓只有这一处。<b>按方块注册表查,不按物品</b>:{@code air} 是"把这一格挖空",按物品查会被当成未知物品拒掉。
     * 能不能建的判据是 {@link #unbuildableReason}:拿它当拒绝理由。
     *
     * @throws IllegalArgumentException 读不通(原版解析器的原话照转)、或这种方块盖不了(施工工具方块这类)。液体读得通:它不放,
     *                                  回执在 {@code liquid} 一类里说({@link com.dwinovo.numen.task.build.BuildOutstanding})
     */
    public static Resolved resolve(String written) {
        String trimmed = written.trim();
        BlockState state = parse(trimmed, false).blockState();
        String no = unbuildableReason(state);
        if (no != null) {
            throw new IllegalArgumentException(trimmed + " — " + no);
        }
        // 没有自己物品的方块(盆栽、海带的茎、洞穴藤蔓的茎……)照常读成一格:整份设计不因为其中一格放不出来就一格不放。
        // 记账物品是空气,这一格轮到时不放,回执里归在 no_item 下({@link Target#noItem})
        net.minecraft.world.item.Item item = materialItem(state.getBlock());
        String name = trimmed.contains("[") ? trimmed.substring(0, trimmed.indexOf('[')).trim() : trimmed;
        return new Resolved(state, item, name.contains(":") ? name.split(":", 2)[1] : name);
    }

    /**
     * {@code /setblock} 写法读成方块(状态、写明了的属性、NBT):全仓调原版解析器的唯一一处。{@link #resolve} 在它上面加"能不能建",
     * 形状({@code numen.shape})的图案与转向只要读懂、不问能不能建,所以带 NBT 的写法由它们自己要。
     *
     * @throws IllegalArgumentException 读不通(原版解析器的话照转)
     */
    public static net.minecraft.commands.arguments.blocks.BlockStateParser.BlockResult parse(String written,
                                                                                            boolean allowNbt) {
        try {
            return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), written.trim(), allowNbt);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException bad) {
            // 原版解析器的话已经足够具体("Unknown block type"、"does not have property"),原样转述,不再翻译一遍
            throw new IllegalArgumentException(written.trim() + " — " + bad.getMessage());
        }
    }

    /**
     * 一个方块状态写成 {@code /setblock} 的写法,只写和默认状态不一样的属性({@code minecraft:oak_stairs[facing=east]}):{@link #parse}
     * 的逆,读回来是同一个状态。读世界得到的方块经这里写成名字。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String written(BlockState state) {
        BlockState base = state.getBlock().defaultBlockState();
        StringBuilder out = new StringBuilder(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        String sep = "[";
        for (Property<?> p : state.getProperties()) {
            if (!state.getValue(p).equals(base.getValue(p))) {
                out.append(sep).append(p.getName()).append('=').append(((Property) p).getName(state.getValue(p)));
                sep = ",";
            }
        }
        return sep.equals(",") ? out.append(']').toString() : out.toString();
    }

    /**
     * 落位前的归一。
     *
     * @return 清掉运行态之后的状态(原状态不变)
     */
    public static BlockState normalize(BlockState state) {
        if (state == null) {
            return null;
        }
        // 堆肥进度与锅里装的东西都是运行态,不是摆设:图纸里存着"攒了三层的堆肥桶"
        // 或"装着岩浆的锅",照字面摆是错的,归一成空的那一档
        if (state.is(Blocks.COMPOSTER)) {
            return Blocks.COMPOSTER.defaultBlockState();
        }
        if (state.is(BlockTags.CAULDRONS)) {
            return Blocks.CAULDRON.defaultBlockState();
        }
        // 生长阶段:按<b>属性名</b>认,不认那八个具体常量。原版有 AGE_1 到 AGE_25 八个
        // 不同上限的版本,模组还会定义自己的——列常量意味着每来一个新上限就漏一次,而
        // 名字叫 "age" 的整数属性就是生长阶段,这一点原版与模组是一致的。
        for (Property<?> p : state.getProperties()) {
            if (p instanceof net.minecraft.world.level.block.state.properties.IntegerProperty age
                    && "age".equals(age.getName())) {
                state = state.setValue(age, java.util.Collections.min(age.getPossibleValues()));
                break;   // 一块方块只会有一种 age
            }
        }
        // 蜂巢里攒的蜜是运行态,而且是凭空产出:照抄一个 honey_level=5 的蜂巢,
        // 玩家拿剪刀直接剪出三个蜂巢。孵化进度同理会提前刷出海龟。
        if (state.hasProperty(BlockStateProperties.LEVEL_HONEY)) {
            state = state.setValue(BlockStateProperties.LEVEL_HONEY, 0);
        }
        if (state.hasProperty(BlockStateProperties.HATCH)) {
            state = state.setValue(BlockStateProperties.HATCH, 0);
        }
        if (state.hasProperty(BlockStateProperties.STAGE)) {
            state = state.setValue(BlockStateProperties.STAGE, 0);
        }
        // 洞穴藤蔓结不结果同理,而且这一条是白送:一格花一颗发光浆果,玩家伸手一摘
        // 把那颗原样收回、藤蔓还留着——一整面二百格的藤蔓墙造价为零。
        if (state.hasProperty(BlockStateProperties.BERRIES)) {
            state = state.setValue(BlockStateProperties.BERRIES, false);
        }
        // 含水是从周围的水推出来的,不是作者定的——照抄等于凭空造水,而我们连
        // 主动放水都不做。去掉的水不悄悄丢:{@code Target.heldWater} 记着,回执的 liquid 一类说有几格
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        // 活塞伸出与否是运行态
        if (state.hasProperty(BlockStateProperties.EXTENDED)) {
            state = state.setValue(BlockStateProperties.EXTENDED, false);
        }
        // 反过来的一条:建出来的树叶就是"手放树叶"。不置 persistent,自然树的
        // 快照照放会当场腐烂,放一片烂一片永远建不完
        if (state.hasProperty(BlockStateProperties.PERSISTENT)) {
            state = state.setValue(BlockStateProperties.PERSISTENT, true);
        }
        return state;
    }

    /**
     * 这个方块该按<b>哪件物品</b>记账——有些方块没有自己的物品,得拿别的料去做。
     *
     * <p>耕地和土径是用锄/锹在土上加工出来的,算土;高草与大蕨类没有自己的方块物品,
     * 是两株矮的长成的,算矮的那个(件数在 {@code Target#materialCount})。
     *
     * <p>报价、逐格闸门与实扣都按这里的物品数,不能有第二张表:取 {@code asItem()} 的话
     * 耕地或土径的物品是空气,预检永远数不到、逐格闸门永远过不去。
     *
     * @return 记账用的物品;推不出返回空气(调用方据此整格跳过)
     */
    public static net.minecraft.world.item.Item materialItem(
            net.minecraft.world.level.block.Block block) {
        net.minecraft.world.item.Item override = overrideItem(block);
        return override != null ? override : block.asItem();
    }

    /** 方块自述给不出正确答案的那几种——真特例,不是懒。 */
    private static net.minecraft.world.item.Item overrideItem(
            net.minecraft.world.level.block.Block block) {
        // 耕地与土径是拿锄/锹在土上加工出来的。它们自述的是自己(那两件物品存在),
        // 但人手上没有"一块耕地"可放。
        if (block instanceof net.minecraft.world.level.block.FarmBlock
                || block instanceof net.minecraft.world.level.block.DirtPathBlock) {
            return net.minecraft.world.item.Items.DIRT;
        }
        return null;
    }

    /**
     * 没有哪件物品能直接放出这个方块,说清它是什么变来的:盆栽是先放花盆再把花种进去,其余是别的方块长出来、或原版自己生成的。
     * 只说得出来的才说,说不出的不猜。
     */
    public static String noItemReason(net.minecraft.world.level.block.Block block) {
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (block instanceof net.minecraft.world.level.block.FlowerPotBlock pot) {
            String plant = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(pot.getPotted()).getPath();
            return id + " has no item of its own: it is a flower_pot with " + plant + " planted in it, so place the "
                    + "flower_pot first and put the " + plant + " in by hand";
        }
        return id + " has no item of its own: no item places it directly, it forms from another block or by growing";
    }

    /**
     * 这一格能不能建;不能建的话,<b>说清为什么</b>。
     *
     * @return null = 可以建;否则是给模型看的一句话
     */
    public static String unbuildableReason(BlockState state) {
        if (state == null) {
            return null;
        }
        if (state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.JIGSAW) || state.is(Blocks.STRUCTURE_BLOCK)) {
            return "structure tooling blocks are not part of a building";
        }
        // 活塞头与移动中的活塞是活塞自己伸出去的产物,不是能摆的东西。我们在
        // normalize 里已经把 EXTENDED 归一成收回,照放一个头就是一块无主的孤块。
        if (state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonHeadBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.piston.MovingPistonBlock) {
            return "a piston head belongs to an extended piston, not to a build";
        }
        return null;
    }
}
