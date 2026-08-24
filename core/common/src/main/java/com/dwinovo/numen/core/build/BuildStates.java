package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;

/**
 * 落位前把方块状态<b>归一</b>,以及判定一格能不能建——建造与图纸两条入口的唯一收口。
 *
 * <p>此前这两件事散在四处各判各的:图纸加载器筛掉一批方块、调色板筛掉另一批、
 * {@code Target} 的构造器里偷偷把树叶改成不会腐烂、而落位时又把图纸里那些运行态
 * 原样写进世界。同一个判断分四个地方做,改一处就漏三处。
 *
 * <p>归一的判据只有一条:<b>不是作者定的,就不该照抄</b>。图纸是某个世界某一刻的
 * 快照,里面混着大量世界自己算出来的东西——作物长到第几节、方块含不含水、活塞伸没
 * 伸出去、堆肥桶攒了多少、锅里装的什么。照字面摆下去,就会凭空长出一片熟麦子、凭空
 * 造出水来、摆出一口装着岩浆的锅。{@link BuildValidity} 那张"作者属性"白名单是同
 * 一条判据的另一面:它管比对时忽略什么,这里管落位前清掉什么。
 */
public final class BuildStates {

    private BuildStates() {}

    /**
     * 落位前的归一。同一份状态,建造与图纸走同一条。
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
        // 主动放水都不做
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        // 活塞伸出与否是运行态
        if (state.hasProperty(BlockStateProperties.EXTENDED)) {
            state = state.setValue(BlockStateProperties.EXTENDED, false);
        }
        // 反过来的一条:建出来的树叶就是"手放树叶"。不置 persistent,自然树的
        // 蓝图照放会当场腐烂,放一片烂一片永远建不完
        if (state.hasProperty(BlockStateProperties.PERSISTENT)) {
            state = state.setValue(BlockStateProperties.PERSISTENT, true);
        }
        return state;
    }

    /**
     * 这是双格方块的<b>次半</b>吗——床头、门/高草的上半。
     *
     * <p>次半不进目标集,由主半的 {@code setPlacedBy} 自己造出来。理由是床:床的两半
     * 同 y,施工顺序按 z 递增,facing=north 时<b>床头先落位</b>,而
     * {@code BedBlock#setPlacedBy} 会往 {@code pos.relative(facing)} 再写一块床头——
     * 那一格在目标集之外,不记账、不算脚手架、收工不清。一张床收一件料,世界里留下
     * 三块床方块;若那一格恰好是已砌好的内墙(床头贴墙是最常见的摆法),它被覆写,
     * 下一遍又判成"被拆了"重建,来回死转。
     *
     * <p>门和高草不会出这事(另一半恒在正上方,按 y 排序主半必先落位),但一并剔除
     * 更省事,也让分母诚实:次半从来不是我们放的,不该占一格待办。
     */
    public static boolean isSecondaryHalf(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART)
                        == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
            return true;
        }
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
    }

    /**
     * 这个方块该按<b>哪件物品</b>记账——有些方块没有自己的物品,得拿别的料去做。
     *
     * <p>耕地和土径是用锄/锹在土上加工出来的,算土;高草与大蕨类没有自己的方块物品,
     * 是两株矮的长成的,算矮的那个(件数在 {@code Target#materialCount})。
     *
     * <p>这张表必须两条入口共用。此前它只挂在工具那条入口上,图纸那条直接取
     * {@code asItem()}——于是含耕地或土径的社区图纸,目标格的物品是空气:预检永远
     * 数不到、逐格闸门永远过不去,最后报"还差 air x37"。而 {@code dirt_path} 正是
     * 我们自己在装饰指南里教着用的料。
     *
     * @return 记账用的物品;推不出返回空气(调用方据此整格跳过)
     */
    public static net.minecraft.world.item.Item materialItem(
            net.minecraft.world.level.block.Block block) {
        net.minecraft.world.item.Item override = overrideItem(block);
        return override != null ? override : block.asItem();
    }

    /**
     * 这个方块该按哪件物品记账——<b>先问方块自己,问不出来才查表</b>。
     *
     * <p>"问方块自己"就是中键取方块那条路({@code getCloneItemStack})。它是原版给每个
     * 方块留的自述接口:小麦答种子、洞穴藤蔓答发光浆果、竹笋答竹子、连枝的瓜藤答瓜种、
     * 带花的花盆答盆里那株花。这些答案我们此前是一行一行写死的——十三行,每行都是被咬过
     * 一次才补上的,而且只覆盖原版:模组的作物、模组的藤蔓一个都不认。问方块自己就没有
     * 这个问题,新方块自动有答案。
     *
     * <p>表只留<b>方块自述给不出正确答案</b>的那种。耕地与土径就是:它们的自述是耕地和
     * 土径本身(这两件物品确实存在),但人手上没有"一块耕地"——那是拿锄头在土上刨出来的,
     * 所以按土算。这一档是真特例,不是懒。
     *
     * @param level 用来问方块的世界;为 null 时退回方块自己的物品(工具入口没有世界)
     */
    public static net.minecraft.world.item.Item materialItem(
            BlockState state, net.minecraft.world.level.LevelReader level,
            net.minecraft.core.BlockPos pos) {
        if (state == null) {
            return net.minecraft.world.item.Items.AIR;
        }
        net.minecraft.world.item.Item override = overrideItem(state.getBlock());
        if (override != null) {
            return override;
        }
        // 带方块实体的方块<b>不问</b>。它们的自述会去读 {@code pos} 那一格<b>世界里</b>的
        // 方块实体,而那一格此刻放的不是图纸里这块——旗帜、陶罐、潜影盒都是这样。后果很
        // 具体:探针那一格恰好立着一面红旗,图纸里所有素白旗的记账物品就全变成红旗,
        // 预检索要红旗、逐格闸门查红旗、实扣扣红旗,而落位放的是白旗。
        //
        // 别处解决这个问题靠养一个一次性世界(那里没有别人的方块实体);我们没有,那就
        // 只问答案与世界无关的那些方块。花盆、竹笋、瓜藤、藤蔓段都不带方块实体,所以
        // 该拿到的答案一个都没少;带方块实体的那些本来也不靠这条路(旗帜走的是
        // {@link #strictItem},读的是图纸自己那份数据)。
        if (level != null && !state.hasBlockEntity()) {
            try {
                // 1.21.2+ 公开入口在 BlockStateBase 上,末参 includeData=false(这里只问无方块实体的方块)
                var picked = state.getCloneItemStack(level, pos, false);
                if (!picked.isEmpty()) {
                    return picked.getItem();
                }
            } catch (RuntimeException ignored) {
                // 问不出来就退回下面那条,不该让一格坏掉整张图纸
            }
        }
        return state.getBlock().asItem();
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
     * 这一格要收<b>哪几叠</b>料——空表示走默认的"一件本方块的物品"。
     *
     * <p>一格一件是特例而不是通则。带花的花盆就是<b>花盆加那株花两件东西</b>,正因如此
     * 它没有自己的物品;而"按方块的物品收一件"这条路在这里根本没有答案——那件物品是空气。
     * 通行做法之一是就此整格丢掉,建出来院子里少二十一个花盆而无人知晓;另一条是老老实实
     * 收两件。后者才对,而且它不是特殊照顾,是把"一格可以要几叠料"这件事本来的形状还给它。
     *
     * <p>旗帜是同一条路的另一头:一叠,但要求组件一致(花纹是织布机上一层层染出来的活)。
     *
     * @return 这一格的料单;空表示没有特殊要求
     */
    public static List<BuildTaskRecord.CellNeed> cellNeeds(
            BlockState state, net.minecraft.nbt.CompoundTag safeData,
            net.minecraft.world.level.LevelReader level, net.minecraft.core.BlockPos probe,
            net.minecraft.core.HolderLookup.Provider registries) {
        if (state == null) {
            return List.of();
        }
        // 带花的花盆:盆一件,花一件。盆里那株是什么,问方块自己——中键取方块给的正是
        // 那株植物。不写死一张"哪个盆装哪种花"的对照表(那张表在别处是二十多行的
        // switch),模组的花盆也照样认。
        if (state.getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock pot
                && pot.getPotted() != Blocks.AIR) {
            net.minecraft.world.item.Item plant = materialItem(state, level, probe);
            if (plant == net.minecraft.world.item.Items.AIR) {
                plant = pot.getPotted().asItem();
            }
            if (plant == net.minecraft.world.item.Items.AIR
                    || plant == net.minecraft.world.item.Items.FLOWER_POT) {
                return List.of();   // 空盆,或者问不出盆里是什么:按一件盆走默认口径
            }
            return List.of(
                    new BuildTaskRecord.CellNeed(new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.item.Items.FLOWER_POT), false),
                    new BuildTaskRecord.CellNeed(new net.minecraft.world.item.ItemStack(plant), false));
        }
        net.minecraft.world.item.ItemStack exact = strictItem(state, safeData, registries);
        return exact == null ? List.of() : List.of(new BuildTaskRecord.CellNeed(exact, true));
    }

    /**
     * 这一格的料要不要按<b>组件全等</b>收——旗帜是唯一一种。
     *
     * <p>旗帜的花纹我们照搬(它是设计的一部分),而花纹是玩家在织布机上一层层染出来的
     * 活。按一面白旗收料就是把那份活白送。所以收的是<b>带着这些花纹的那面旗帜本身</b>。
     *
     * <p>做法是把物品拼成一段 NBT 交给原版自己的编解码器去读,而不是手搭组件:方块实体
     * 里那份 {@code patterns} 和物品上那个花纹组件本来就是同一套编码,借道过去必然一致,
     * 手搭迟早会在某个版本上错位。
     *
     * @return 要精确收的那一叠;这一格不需要精确返回 null
     */
    public static net.minecraft.world.item.ItemStack strictItem(
            BlockState state, net.minecraft.nbt.CompoundTag safeData,
            net.minecraft.core.HolderLookup.Provider registries) {
        if (state == null || safeData == null || !state.is(net.minecraft.tags.BlockTags.BANNERS)
                || !safeData.contains("patterns")) {
            return null;
        }
        net.minecraft.world.item.Item item = state.getBlock().asItem();
        if (item == net.minecraft.world.item.Items.AIR) {
            return null;
        }
        net.minecraft.nbt.CompoundTag itemTag = new net.minecraft.nbt.CompoundTag();
        itemTag.putString("id", net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(item).toString());
        itemTag.putInt("count", 1);
        net.minecraft.nbt.CompoundTag components = new net.minecraft.nbt.CompoundTag();
        components.put("minecraft:banner_patterns", safeData.get("patterns").copy());
        itemTag.put("components", components);
        // 1.21.6+ 没有 ItemStack.parse 便捷方法了,codec 互转统一放 BlueprintSafety。
        return BlueprintSafety.parseItem(registries, itemTag)
                .filter(s -> !s.isEmpty())
                .orElse(null);
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
        if (state.getBlock() instanceof LiquidBlock) {
            // 能力边界,得说是边界:回一句"未知方块"的话,名字明明是对的,模型只会
            // 以为自己拼错了,换个写法再试一遍
            return "she does not place or drain liquids; leave water and lava out of it"
                    + " and dig the basin instead, or let the player pour it";
        }
        return null;
    }
}
