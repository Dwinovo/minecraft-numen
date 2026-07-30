package com.dwinovo.numen.core.task;

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

    /** 各种"长到第几阶段"。作物、竹子、海带、可可、甘蔗都在这一族里。 */
    private static final List<Property<Integer>> AGES = List.of(
            BlockStateProperties.AGE_1, BlockStateProperties.AGE_2, BlockStateProperties.AGE_3,
            BlockStateProperties.AGE_4, BlockStateProperties.AGE_5, BlockStateProperties.AGE_7,
            BlockStateProperties.AGE_15, BlockStateProperties.AGE_25);

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
        for (Property<Integer> age : AGES) {
            if (state.hasProperty(age)) {
                state = state.setValue(age, 0);
                break;   // 一块方块只会有一种 age
            }
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
     * 图纸带来的方块实体数据,哪些可以照搬——<b>白名单</b>。
     *
     * <p>只有<b>装饰性</b>的才搬:告示牌的字、旗帜的花纹、陶罐的纹样。容器里的东西
     * 一律不搬,而这不是保守,是必须:图纸是文件,可以任意编辑、可以从网上下载。
     * 照搬容器内容意味着一张塞满钻石的图纸建出来就是白送——那不是"还原了作者的
     * 设计",那是凭空造物品。同理刷怪笼的刷怪数据、战利品表、命令方块的命令,
     * 都不是"这栋房子长什么样"的一部分。
     *
     * <p>用白名单而不是黑名单,和对账那张"作者属性"名单同一个道理:黑名单是开放
     * 集合,每来一个新方块就可能带一个新的危险字段,只能被咬一次补一条;白名单
     * 是封闭集合,一次定完,此后任何新方块都自动落在安全的一侧。
     *
     * @return 可以照搬的那部分;没有可搬的返回 null
     */
    public static net.minecraft.nbt.CompoundTag safeBlockEntityData(
            BlockState state, net.minecraft.nbt.CompoundTag data) {
        if (state == null || data == null || data.isEmpty()) {
            return null;
        }
        List<String> keep;
        if (state.is(net.minecraft.tags.BlockTags.ALL_SIGNS)) {
            keep = List.of("front_text", "back_text", "is_waxed");
        } else if (state.is(net.minecraft.tags.BlockTags.BANNERS)) {
            keep = List.of("patterns");
        } else if (state.is(Blocks.DECORATED_POT)) {
            keep = List.of("sherds");
        } else {
            return null;
        }
        net.minecraft.nbt.CompoundTag out = new net.minecraft.nbt.CompoundTag();
        if (data.contains("id")) {
            out.putString("id", data.getString("id"));
        }
        for (String key : keep) {
            if (data.contains(key)) {
                out.put(key, data.get(key).copy());
            }
        }
        return out.size() <= 1 ? null : out;   // 只剩一个 id 等于没数据
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
        if (state.getBlock() instanceof LiquidBlock) {
            // 能力边界,得说是边界:回一句"未知方块"的话,名字明明是对的,模型只会
            // 以为自己拼错了,换个写法再试一遍
            return "she does not place or drain liquids; leave water and lava out of it"
                    + " and dig the basin instead, or let the player pour it";
        }
        return null;
    }
}
