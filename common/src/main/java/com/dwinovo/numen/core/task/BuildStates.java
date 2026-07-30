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
        if (block instanceof net.minecraft.world.level.block.FarmBlock
                || block instanceof net.minecraft.world.level.block.DirtPathBlock) {
            return net.minecraft.world.item.Items.DIRT;
        }
        // 花盆:带花的那些没有自己的物品。按空盆记账,盆里那株算搭头——一株樹苗
        // 值不了什么,而整格丢掉就是院子里少了二十一个花盆,那是看得见的。
        if (block instanceof net.minecraft.world.level.block.FlowerPotBlock) {
            return net.minecraft.world.item.Items.FLOWER_POT;
        }
        // 作物与藤蔓类:方块态没有物品,种下去的是种子/果实。归一已经把生长阶段
        // 清成 0,所以这里给的正是"刚种下"该花的那件东西。
        if (block == Blocks.WHEAT) {
            return net.minecraft.world.item.Items.WHEAT_SEEDS;
        }
        if (block == Blocks.CARROTS) {
            return net.minecraft.world.item.Items.CARROT;
        }
        if (block == Blocks.POTATOES) {
            return net.minecraft.world.item.Items.POTATO;
        }
        if (block == Blocks.BEETROOTS) {
            return net.minecraft.world.item.Items.BEETROOT_SEEDS;
        }
        if (block == Blocks.MELON_STEM || block == Blocks.ATTACHED_MELON_STEM) {
            return net.minecraft.world.item.Items.MELON_SEEDS;
        }
        if (block == Blocks.PUMPKIN_STEM || block == Blocks.ATTACHED_PUMPKIN_STEM) {
            return net.minecraft.world.item.Items.PUMPKIN_SEEDS;
        }
        if (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) {
            return net.minecraft.world.item.Items.GLOW_BERRIES;
        }
        if (block == Blocks.SWEET_BERRY_BUSH) {
            return net.minecraft.world.item.Items.SWEET_BERRIES;
        }
        if (block == Blocks.BAMBOO_SAPLING) {
            return net.minecraft.world.item.Items.BAMBOO;
        }
        if (block == Blocks.KELP_PLANT) {
            return net.minecraft.world.item.Items.KELP;
        }
        if (block == Blocks.TRIPWIRE) {
            return net.minecraft.world.item.Items.STRING;
        }
        if (block == Blocks.REDSTONE_WIRE) {
            return net.minecraft.world.item.Items.REDSTONE;
        }
        if (block == Blocks.TALL_GRASS) {
            return net.minecraft.world.item.Items.SHORT_GRASS;
        }
        if (block == Blocks.LARGE_FERN) {
            return net.minecraft.world.item.Items.FERN;
        }
        return block.asItem();
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
     * 图纸里的实体,哪些算建筑的一部分——同样是白名单。
     *
     * <p>只收<b>摆设</b>:展示框、盔甲架、画。它们钉在墙上、立在院里,拆了这栋房子
     * 就不完整。活物不收——图纸里存着的牛马村民不是设计,照搬等于凭空造生物;矿车、
     * 船同理,那是玩家自己的东西。
     *
     * <p>而且只收<b>躯壳</b>:展示框里的物品、盔甲架身上的装备一律剥掉,理由和容器
     * 内容一样——图纸是文件,照搬里面的东西就是凭空造物品。玩家自己往框里放东西,
     * 那一步本来就该是他的。
     *
     * @return 可以生成的那部分;不收返回 null
     */
    public static net.minecraft.nbt.CompoundTag safeEntityData(net.minecraft.nbt.CompoundTag data) {
        if (data == null || !data.contains("id")) {
            return null;
        }
        String id = data.getString("id");
        if (!id.equals("minecraft:item_frame") && !id.equals("minecraft:glow_item_frame")
                && !id.equals("minecraft:armor_stand") && !id.equals("minecraft:painting")) {
            return null;
        }
        net.minecraft.nbt.CompoundTag out = data.copy();
        // 躯壳留下,身上的东西剥掉
        for (String carried : new String[]{"Item", "Items", "ArmorItems", "HandItems", "equipment"}) {
            out.remove(carried);
        }
        // 挂件(展示框、画)的<b>锚点</b>是一个绝对方块坐标,存在自己的 NBT 里,
        // 而不是由位置推出来的。不改它的话:实体读档时把锚点设成导出世界那个坐标,
        // 挂件每 100 刻自查一次"我挂的那面墙还在吗"——查的是源世界的坐标。那儿是空
        // 的就五秒后掉落(玩家付了一个展示框,墙上什么也没有,地上多个掉落物);
        // 那儿恰好有方块就留着,但下次区块重载会把它<b>瞬移回源世界坐标</b>。
        // 这几个键在这里删掉,由落位方自己写。
        for (String anchor : new String[]{"block_pos", "TileX", "TileY", "TileZ"}) {
            out.remove(anchor);
        }
        return out;
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
