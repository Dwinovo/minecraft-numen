package com.dwinovo.numen.task.build;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.platform.NumenServices;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把施工图里的一格放进世界:施工期间每一格都只经这里落下,两种放法。她站在哪儿、离这一格多远、中间隔着什么都不看——
 * 服务端的放置流程本身不查这些。
 *
 * <h2>手放</h2>
 * 一次右键:主手拿着那件物品(生存模式从背包切、免耗材临时拿一叠再还原),{@code gameMode.useItemOn}。音效、邻居更新、扣料、
 * 放置事件(领地类模组可取消)、模组方块自己的放置逻辑全是原版流程自己产生的。点的是<b>这一格自己</b>:轮到放的格里只有空气或
 * 液体,原版对可顶替的格原地放,所以悬空的格也有得点,也不去碰邻居(领地模组看到的被点方块就是要放的这一格)。
 *
 * <h2>一次点击的参数</h2>
 * 原版的放置规则按点击给状态:点哪一面(原木的轴、漏斗的口、活板门与台阶的上下)、点在面上哪一点(楼梯与台阶的上下半、门的
 * 合页左右)、她的朝向(楼梯、熔炉、箱子朝哪,活塞与侦测器的六向,火把、按钮、梯子、灯笼贴哪一面,旗、牌、头颅的 16 向)。这些就是
 * {@link Click} 的字段。朝向只是这一下点击的参数:点之前设上、点完还原,同一刻内完成,谁也看不到;给看着她的人看的转头是
 * {@link BuildShowmanship} 的事。点击时按着潜行:箱子按潜行才不并成大箱子。
 *
 * <h2>选哪一下</h2>
 * 候选是固定的一张表({@link #CLICKS}):六个面 × 每面四个象限点 × 十二种朝向(四个正方向 × 平视、俯视、仰视),共 288 下;
 * 再加十二个斜向 × 三种俯仰、点顶面的 36 下,只有 16 向的方块(旗、牌、头颅)读得出斜向,它们不看点在哪。合计
 * {@value #CLICK_COUNT} 下,这就是一格最坏要问的次数。逐个用原版自己的放置规则({@code BlockItem} 的
 * {@code updatePlacementContext} 与 {@code getPlacementState})<b>模拟</b>这一下会放成什么——只读,不动世界——拿
 * {@link BuildTaskRecord.Target#matches} 与目标比,那是施工里对账用的同一个判据。与目标状态一字不差的那一下立刻用;没有一字不差的,
 * 用第一下对得上的;一下都对不上才直接写。没有状态位的整块方块第一下就中。手放点下去之后世界没变(领地模组取消了事件),
 * 这一格就是放不下,不拿直接写绕过去。
 *
 * <p>实测(1.21.1,NeoForge 开发环境,热身之后):模拟一下约 0.5 微秒,问满 {@value #CLICK_COUNT} 下(没有一字不差的那一下、
 * 或一下都对不上)约 0.15 到 0.2 毫秒。整块方块与正放的楼梯问 1 到 4 下,倒置的楼梯与上半台阶 49 下。真点的那一下走原版整套
 * 放置流程,比挑点击贵得多(冷态量到 0.2 到 1 毫秒)。
 *
 * <h2>直接写</h2>
 * 手放做不出那个样子时的退路:{@code setBlock} 写下期望的状态,补一次 {@code setPlacedBy},逐格扣料。做不出的情形有两种:
 * 哪一下点击原版都放不出这个状态(放置规则给不出它,或它在那儿立不住)、一格要不止一件料(双层半砖、堆叠的雪、多根蜡烛)。
 *
 * <p>直接写的格数如实进回执({@link BuildCompanionTask.Placed#setDirectly}):它量的是手放做不到的格有多常见。两种放法放的、扣的、
 * 回执报的都是背包里同一叠({@link Stacks#pick}):手放是拿到手里的那一叠,直接写是把那一叠的数据灌进方块。
 *
 * <p><b>分工</b>——本类只管一格怎么落进世界;这一格该不该放、料够不够、放完怎么记账是 {@link BuildCompanionTask} 的事,
 * 演出是 {@link BuildShowmanship}。
 */
final class BuildPlacer {

    /** 一格是怎么落进世界的。 */
    enum Way { BY_HAND, SET_DIRECTLY }

    /** 一格落下了:怎么落的,以及为它花掉的每一叠(样子与件数,免耗材为空)——回执说"用了哪一叠"读的就是它。 */
    record Placement(Way way, List<ItemStack> spent) {}

    /**
     * 一下点击:点这一格的 {@code side} 面上 {@code (x, y, z)} 这一点(格内坐标,0 到 1),点的时候她朝 {@code yaw}、俯仰
     * {@code pitch}(原版口径:南是 0,向下为正)。
     */
    private record Click(Direction side, double x, double y, double z, float yaw, float pitch) {

        BlockHitResult on(BlockPos cell) {
            return new BlockHitResult(new Vec3(cell.getX() + x, cell.getY() + y, cell.getZ() + z), side, cell, false);
        }
    }

    /** 面上的象限点:面内两个方向各取四分之一与四分之三,中线两侧各有一点。 */
    private static final double[] QUARTERS = {0.25, 0.75};
    /** 平视、俯视、仰视:原版按"最先朝着哪个方向"挑贴附面,竖直方向排第一要低头或抬头。 */
    private static final float[] PITCHES = {0.0F, 90.0F, -90.0F};
    /** 朝向分 16 档,每档 22.5 度;每四档是一个正方向。 */
    private static final int YAWS = 16;

    private static final List<Click> CLICKS = clicks();
    private static final int CLICK_COUNT = 324;

    private static List<Click> clicks() {
        List<Click> out = new ArrayList<>(CLICK_COUNT);
        // 顶面在前:最常见的样子(立在地上、下半)点顶面就出
        Direction[] sides = {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (Direction side : sides) {
            for (double u : QUARTERS) {
                for (double v : QUARTERS) {
                    for (float pitch : PITCHES) {
                        for (int turn = 0; turn < YAWS; turn += YAWS / 4) {
                            out.add(at(side, u, v, turn, pitch));
                        }
                    }
                }
            }
        }
        for (float pitch : PITCHES) {
            for (int turn = 0; turn < YAWS; turn++) {
                if (turn % (YAWS / 4) != 0) {
                    out.add(at(Direction.UP, QUARTERS[0], QUARTERS[0], turn, pitch));
                }
            }
        }
        if (out.size() != CLICK_COUNT) {
            throw new IllegalStateException("the click table holds " + out.size() + " clicks, not " + CLICK_COUNT);
        }
        return List.copyOf(out);
    }

    /** {@code side} 面上、面内两个方向落在 {@code u}、{@code v} 处的一点,朝向第 {@code turn} 档。 */
    private static Click at(Direction side, double u, double v, int turn, float pitch) {
        double flat = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0;
        float yaw = turn * (360.0F / YAWS) - 180.0F;
        return switch (side.getAxis()) {
            case X -> new Click(side, flat, u, v, yaw, pitch);
            case Y -> new Click(side, u, flat, v, yaw, pitch);
            case Z -> new Click(side, u, v, flat, yaw, pitch);
        };
    }

    private final NumenPlayer player;
    private final BuildInventory inv;
    private final boolean consumeMaterials;

    BuildPlacer(NumenPlayer player, BuildInventory inv, boolean consumeMaterials) {
        this.player = player;
        this.inv = inv;
        this.consumeMaterials = consumeMaterials;
    }

    /**
     * 把这一格放进世界。
     *
     * @param cost  这一格要花几件;免耗材时由调用方给 0
     * @return 怎么放的、花了哪几叠;放不下(事件被取消、写不进去)为 null,世界没变
     */
    Placement place(BuildTaskRecord.Target target, int cost) {
        if (target.materialCount() <= 1 && target.item() instanceof BlockItem item
                && item.getBlock().isEnabled(player.level().enabledFeatures())) {
            HandTry tried = byHand(target, item);
            if (tried.hand() != Hand.NONE) {
                return tried.hand() == Hand.PLACED ? new Placement(Way.BY_HAND, tried.spent()) : null;
            }
        }
        return setDirectly(target, cost);
    }

    // ------------------------------------------------------------------
    // 手放
    // ------------------------------------------------------------------

    /** 手放的结局:没有可点的点击(该直接写)、点下去落成了、点下去世界没让落。 */
    private enum Hand { NONE, PLACED, REFUSED }

    /** 手放试了一遍:结局,以及点下去时拿在手里的那一叠(生存时,原版扣的就是它;免耗材为空)。 */
    private record HandTry(Hand hand, List<ItemStack> spent) {
        static final HandTry NONE = new HandTry(Hand.NONE, List.of());
        static final HandTry REFUSED = new HandTry(Hand.REFUSED, List.of());
    }

    /**
     * 试着手放:挑出放得出目标的那一下({@link #choose}),摆成它的朝向、按着潜行点下去;朝向与潜行点完还原。一下都对不上是
     * {@link Hand#NONE}。
     */
    private HandTry byHand(BuildTaskRecord.Target target, BlockItem item) {
        float yaw = player.getYRot();
        float head = player.getYHeadRot();
        float pitch = player.getXRot();
        boolean sneaking = player.isShiftKeyDown();
        player.setShiftKeyDown(true);
        try {
            Click click = choose(target, item);
            if (click == null) {
                return HandTry.NONE;
            }
            face(click);
            return press(target, item, click.on(target.pos()));
        } finally {
            player.setShiftKeyDown(sneaking);
            player.setYRot(yaw);
            player.setYHeadRot(head);
            player.setXRot(pitch);
        }
    }

    /**
     * 放得出目标的那一下:与目标状态一字不差的立刻交出,否则是第一下 {@link BuildTaskRecord.Target#matches} 认可的;一下都没有
     * 为 null。挑的过程里她的朝向跟着每个候选变,交出之后由调用方再摆一次。
     */
    private Click choose(BuildTaskRecord.Target target, BlockItem item) {
        ItemStack held = new ItemStack(item);
        Click accepted = null;
        for (Click click : CLICKS) {
            face(click);
            BlockState state = simulate(target, item, held, click.on(target.pos()));
            if (state == null || !target.matches(state)) {
                continue;
            }
            if (state.equals(target.desiredState())) {
                return click;
            }
            if (accepted == null) {
                accepted = click;
            }
        }
        return accepted;
    }

    /** 摆成这一下点击的朝向。原版读身体的朝向(水平方向、16 向)也读头的朝向(最先朝着哪个方向),两个一起设。 */
    private void face(Click click) {
        player.setYRot(click.yaw());
        player.setYHeadRot(click.yaw());
        player.setXRot(click.pitch());
    }

    /**
     * 这一下点击原版会放成什么:按这一下造放置上下文,问物品自己;放不出来(站不住、被挡着、不是落在这一格)是 null。
     * 只读,不动世界。
     */
    private BlockState simulate(BuildTaskRecord.Target target, BlockItem item, ItemStack held, BlockHitResult hit) {
        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, held, hit);
        if (!context.canPlace()) {
            return null;
        }
        BlockPlaceContext placing = item.updatePlacementContext(context);
        if (placing == null || !placing.getClickedPos().equals(target.pos())) {
            return null;
        }
        return NumenServices.VANILLA.placementState(item, placing);
    }

    /**
     * 点下去:拿着物品右键。落没落成看世界,不看返回值——物品的 place 可以吃掉点击却什么都没放。世界变了就是放下了,留不留得住
     * 由调用方比对;一格没变是被拒了(事件被取消、保护)。
     */
    private HandTry press(BuildTaskRecord.Target target, BlockItem item, BlockHitResult hit) {
        BlockState before = player.level().getBlockState(target.pos());
        ItemStack restore = null;
        List<ItemStack> spent = List.of();
        if (consumeMaterials) {
            Optional<Stacks.Pick> pick = inv.pick(target.item());
            if (pick.isEmpty()) {
                return HandTry.REFUSED;   // 付得起的闸门刚过,到这儿没了只可能是同刻竞态:这遍放下
            }
            player.hotbar().hold(pick.get().slot());
            spent = List.of(pick.get().sample());   // 原版右键扣的是手里这一叠,就是选中的这一叠
        } else {
            // 免耗材:凭空一叠拿在手里,放完把原来的东西还回去,不动她的真背包
            restore = player.getMainHandItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        }
        try {
            player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        } finally {
            if (restore != null) {
                player.setItemInHand(InteractionHand.MAIN_HAND, restore);
            }
        }
        return player.level().getBlockState(target.pos()) != before ? new HandTry(Hand.PLACED, spent) : HandTry.REFUSED;
    }

    // ------------------------------------------------------------------
    // 直接写
    // ------------------------------------------------------------------

    /**
     * 照图直写这一格,带着原版的方块更新(与手放同一种:邻居当场按原版规则反应)。写不进去就什么都不算:{@code setBlock} 在超出
     * 建造高度时直接返回假、世界毫无变化,照样扣料会让材料凭空消失。
     *
     * <p><b>写下去的是她背包里选中的那一叠,不是同名的白货。</b>要放的那一种由 {@link BuildInventory#pick} 定,方块的数据照原版放置时
     * 对一叠物品做的那几步灌进去({@link BlockItem#updateCustomBlockEntityTag}、{@link BlockEntity#applyComponentsFromItemStack},
     * 原版 {@code BlockItem.place} 里就是这两个调用):潜影盒里的东西、旗帜的花纹、头颅的主人、蜂箱里的蜂,都跟着方块走;
     * 随后扣的、回执报的,还是这一叠。免耗材时没有背包里的叠,用一件新的。
     *
     * <p>写下去之后让原版给这一格自己也复核一次({@code Block.updateFromNeighbourShapes}:六个邻居各自对它的形状说一句,火把没有
     * 依附就变成空气并掉落、栅栏与玻璃板按邻居连上)——手放时这一步在放置规则里,直接写的格绕过了放置规则,所以补在这里。
     * 结果以世界为准,留不留得住由调用方比对。
     *
     * <p>复核之前给方块一次"我被放下了"的回调({@code setPlacedBy}):命名牌、告示牌、部分方块实体靠它初始化,门/床/高草的回调写
     * 另一半(所以次半根本不进目标集)。回调拿到的是同一叠。
     */
    private Placement setDirectly(BuildTaskRecord.Target target, int cost) {
        BlockPos pos = target.pos();
        BlockState desired = target.desiredState();
        Optional<Stacks.Pick> pick = cost > 0 ? inv.pick(target.item()) : Optional.empty();
        if (cost > 0 && pick.isEmpty()) {
            return null;   // 付得起的闸门刚过,到这儿没了只可能是同刻竞态:这遍放下
        }
        ItemStack placing = pick.map(Stacks.Pick::sample).orElseGet(() -> new ItemStack(target.item()));
        if (!player.level().setBlock(pos, desired, Block.UPDATE_ALL)) {
            return null;
        }
        BlockItem.updateCustomBlockEntityTag(player.level(), player, pos, placing);
        BlockEntity entity = player.level().getBlockEntity(pos);
        if (entity != null) {
            entity.applyComponentsFromItemStack(placing);
            entity.setChanged();
        }
        desired.getBlock().setPlacedBy(player.level(), pos, desired, player, placing);
        List<ItemStack> spent = pick.map(p -> inv.consume(p, cost)).orElse(List.of());
        BlockState written = player.level().getBlockState(pos);
        Block.updateOrDestroy(written, Block.updateFromNeighbourShapes(written, player.level(), pos), player.level(), pos,
                Block.UPDATE_ALL);
        return new Placement(Way.SET_DIRECTLY, spent);
    }
}
