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
            // 牌子上的字是纯文本,玩家自己也是白写的,搬过来不产出任何东西
            keep = List.of("front_text", "back_text", "is_waxed");
        } else if (state.is(net.minecraft.tags.BlockTags.BANNERS)) {
            // 花纹搬,但料按<b>带花纹的那面旗帜</b>收(见 strictItem):织布机的活
            // 不能白送
            keep = List.of("patterns");
        } else {
            // 陶罐的纹样不搬。碎片是刷沙刷砾石考古刷出来的稀有掉落,一只四片碎片的
            // 罐子收一件普通陶罐的料就是白送四件稀有物;而按组件精确收料又意味着玩家
            // 得先有一只一模一样的罐子——那还不如让他自己拼。摆一只素罐,纹样留给人。
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
     * <p>身上带的东西<b>照搬,但要照原样付钱</b>:展示框里那把剑、盔甲架身上那套甲,
     * 按<b>组件完全一致</b>的口径计价(见 {@link #payloadStacks})。这条口径是关键——
     * 若只按物品类型收料,玩家交一把白剑就能换来文件里那把"锋利 255 的剑",漏就漏在
     * 这里,而不在于要不要收料。组件全等之后账是平的:交什么得什么。
     *
     * <p>而且<b>收什么就放什么</b>:计价用的那一叠和最终放进框里的必须是同一叠。只从
     * 计价那边剥掉一部分、落位照原样放,等于自己开一个口子。
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
        // 挂件(展示框、画)的<b>锚点</b>是一个绝对方块坐标(TileX/TileY/TileZ),存在
        // 自己的 NBT 里,而不是由位置推出来的。不改它的话:实体读档时把锚点设成导出
        // 世界那个坐标,挂件每 100 刻自查一次"我挂的那面墙还在吗"——查的是源世界的
        // 坐标。那儿是空的就五秒后掉落(玩家付了一个展示框,墙上什么也没有,地上多个
        // 掉落物);那儿恰好有方块就留着,但下次区块重载会把它<b>瞬移回源世界坐标</b>。
        //
        // <p>{@code Pos} 一并删掉,由落位方连锚点一起写:读档时锚点要过一道
        // <b>16 格闸门</b>(锚点离 Pos 太远就判成坏档,丢掉锚点),两个值必须同源。
        // 位置留在这儿只会是导出世界的坐标,而锚点是落位坐标,差的正是搬迁距离。
        for (String anchor : new String[]{"Pos", "TileX", "TileY", "TileZ"}) {
            out.remove(anchor);
        }
        return out;
    }

    /** 摆设身上可能带东西的那几个键。展示框一格,盔甲架四甲两手。 */
    private static final List<String> PAYLOAD_KEYS =
            List.of("Item", "ArmorItems", "HandItems", "equipment");

    /**
     * 这只摆设身上带的东西,要按哪几叠物品计价——每一叠都<b>组件全等</b>地收。
     *
     * <p>不剥、不归一、不打折:收进来的这一叠就是最终放进框里的那一叠。想省事的做法是
     * "只按物品类型收料",那就等于把文件里的附魔白送出去;另一种想省事的做法是"计价
     * 时剥掉一部分组件、落位照原样放",那是自己开的口子。两条都不走。
     *
     * <p>代价说清:玩家得<b>正好有</b>那件东西才装得上。文件里是一把锋利五的剑,他手里
     * 那把白剑不算。装不上就空着框、如实报一笔——不是静默少一件。
     *
     * @return 要收的那些叠(空的槽位不计);没有返回空表
     */
    public static List<net.minecraft.world.item.ItemStack> payloadStacks(
            net.minecraft.nbt.CompoundTag data,
            net.minecraft.core.HolderLookup.Provider registries) {
        if (data == null) {
            return List.of();
        }
        List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
        for (String key : PAYLOAD_KEYS) {
            net.minecraft.nbt.Tag tag = data.get(key);
            if (tag instanceof net.minecraft.nbt.CompoundTag one) {
                addStack(out, one, registries);
            } else if (tag instanceof net.minecraft.nbt.ListTag many) {
                for (net.minecraft.nbt.Tag slot : many) {
                    if (slot instanceof net.minecraft.nbt.CompoundTag c) {
                        addStack(out, c, registries);
                    }
                }
            }
        }
        return out;
    }

    private static void addStack(List<net.minecraft.world.item.ItemStack> out,
                                 net.minecraft.nbt.CompoundTag tag,
                                 net.minecraft.core.HolderLookup.Provider registries) {
        if (tag.isEmpty()) {
            return;   // 空槽位
        }
        net.minecraft.world.item.ItemStack.parse(registries, tag)
                .filter(s -> !s.isEmpty())
                .ifPresent(out::add);
    }

    /** 把摆设身上带的东西整个拿掉——付不起的时候用,框空着比框里凭空多件东西好。 */
    public static void stripPayload(net.minecraft.nbt.CompoundTag data) {
        if (data == null) {
            return;
        }
        for (String key : PAYLOAD_KEYS) {
            data.remove(key);
        }
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
        return net.minecraft.world.item.ItemStack.parse(registries, itemTag)
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
