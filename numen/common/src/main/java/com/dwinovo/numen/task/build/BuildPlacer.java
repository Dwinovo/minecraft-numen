package com.dwinovo.numen.task.build;

import com.dwinovo.numen.api.entity.Faces;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.PlacedBlocks;
import com.dwinovo.numen.platform.NumenServices;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 把施工图里的一格放进世界:施工期间每一格都只经这里落下,两种放法。
 *
 * <h2>手放</h2>
 * 她站在原地,像真玩家那样点一个面——点相邻已有方块朝着这一格的那一面(格内本身是可顶替的方块时原地点),视线就是眼睛到
 * 命中点的那条线(朝向、俯仰由转头得到,命中点由准星的原版拾取给出,所以被别的方块或实体挡着的面点不中),主手拿着那件物品
 * (生存模式从背包切、免耗材临时拿一叠再还原),{@code gameMode.useItemOn},挥手。音效、邻居更新、扣料、放置事件(领地类模组可取消)、
 * 放置记录({@code BlockItem.place} 上的 Mixin)全是原版流程自己产生的。
 *
 * <h2>直接写</h2>
 * 手放做不出那个样子时的退路:{@code setBlock} 写下期望的状态,补一次 {@code setPlacedBy},逐格扣料,记放置记录。
 * 做不出的情形有三种:从她站的地方没有可点的面(悬空、面被挡着、够不着)、点得中的任何位置都放不出这个状态(原版的放置规则
 * 按点击与朝向给不出它,或要看她看不出的邻居)、一格要不止一件料(双层半砖、堆叠的雪、花盆加花)。
 *
 * <h2>选哪一种</h2>
 * 对每个她此刻点得中的面与面上的几处点(中心与四个象限,台阶上下半、门的合页左右由点在面上的位置定),先把她转向那一点,再用原版
 * 自己的放置规则({@code BlockItem} 的 {@code updatePlacementContext} 与 {@code getPlacementState})<b>模拟</b>这一点会放成什么——
 * 只读,不动世界——拿 {@link BuildTaskRecord.Target#matches} 与目标比,那是施工里对账用的同一个判据。有一个点放得出就手放,
 * 其中与目标状态一字不差的优先;一个都没有才直接写。手放点下去之后原版说不行(领地模组取消了事件),这一格就是放不下,
 * 不拿直接写绕过去。
 *
 * <p>直接写的格数如实进回执({@link BuildCompanionTask.Placed#setDirectly}):它量的是手放做不到的格有多常见。
 *
 * <p><b>分工</b>——本类只管一格怎么落进世界;这一格该不该放、料够不够、放完怎么记账是 {@link BuildCompanionTask} 的事,
 * 演出是 {@link BuildShowmanship}。
 */
final class BuildPlacer {

    /** 一格是怎么落进世界的。 */
    enum Way { BY_HAND, SET_DIRECTLY }

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
     * @return 怎么放的;放不下(事件被取消、写不进去)为 null,世界没变
     */
    Way place(BuildTaskRecord.Target target, int cost) {
        if (target.materialCount() <= 1 && target.item() instanceof BlockItem item
                && item.getBlock().isEnabled(player.level().enabledFeatures())) {
            // 点着潜行:点中的方块若自己会响应右键(箱子、工作台、门),原版只有潜行时才往上贴方块;箱子的放置规则也看潜行
            // (潜行就不并成大箱子)。模拟与真点同一个姿态
            boolean sneaking = player.isShiftKeyDown();
            player.setShiftKeyDown(true);
            try {
                Hand hand = byHand(target, item);
                if (hand != Hand.NONE) {
                    return hand == Hand.PLACED ? Way.BY_HAND : null;
                }
            } finally {
                player.setShiftKeyDown(sneaking);
            }
        }
        return setDirectly(target, cost) ? Way.SET_DIRECTLY : null;
    }

    // ------------------------------------------------------------------
    // 手放
    // ------------------------------------------------------------------

    /** 手放的结局:没有可点的点击(该直接写)、点下去落成了、点下去世界没让落。 */
    private enum Hand { NONE, PLACED, REFUSED }

    /**
     * 试着手放:按 {@link #workable} 排好的先后,转向、再问一次原版会放成什么(转头取整依赖转之前的朝向,问的必须是真点那一刻的朝向),
     * 与目标对得上的那一下就点下去:拿着物品右键、挥手。落没落成看世界,不看返回值——物品的 place 可以吃掉点击却什么都没放。
     * 一个点击都对不上是 {@link Hand#NONE}。
     */
    private Hand byHand(BuildTaskRecord.Target target, BlockItem item) {
        for (Faces.Face face : workable(target, item)) {
            BlockHitResult hit = lookAt(face);
            BlockState state = hit == null ? null : simulate(target, item, hit);
            if (state == null || !target.matches(state)) {
                continue;
            }
            BlockState before = player.level().getBlockState(target.pos());
            ItemStack restore = null;
            if (consumeMaterials) {
                int slot = inv.findSlot(target.item());
                if (slot < 0) {
                    return Hand.REFUSED;   // 付得起的闸门刚过,到这儿没了只可能是同刻竞态:这遍放下
                }
                player.hotbar().hold(slot);
            } else {
                // 免耗材:凭空一叠拿在手里,放完把原来的东西还回去,不动她的真背包
                restore = player.getMainHandItem();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
            }
            try {
                InteractionResult result = player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(),
                        InteractionHand.MAIN_HAND, hit);
                if (result.consumesAction()) {
                    player.swing(InteractionHand.MAIN_HAND);
                }
            } finally {
                if (restore != null) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, restore);
                }
            }
            // 世界变了就是放下了,留不留得住由调用方比对;一格没变是被拒了(事件被取消、保护)
            return player.level().getBlockState(target.pos()) != before ? Hand.PLACED : Hand.REFUSED;
        }
        return Hand.NONE;
    }

    /**
     * 放得出目标的点击,与目标状态一字不差的在前,其次是 {@link BuildTaskRecord.Target#matches} 认可的;一个都没有为空表。
     * 候选是 {@link Faces#inSightAll}(从眼睛此刻的位置看得见、够得着的面与面上的点,与站位选择、数格同一个判据)。挑的过程里她每转向
     * 一个候选点,身体的朝向就跟着变,所以这只是个先后,真点之前还要再问一次。
     */
    private List<Faces.Face> workable(BuildTaskRecord.Target target, BlockItem item) {
        List<Faces.Face> exact = new ArrayList<>();
        List<Faces.Face> accepted = new ArrayList<>();
        for (Faces.Face face : Faces.inSightAll(player.level(), player.getEyePosition(), player.blockInteractionRange(),
                target.pos(), null)) {
            BlockHitResult hit = lookAt(face);
            BlockState state = hit == null ? null : simulate(target, item, hit);
            if (state != null && target.matches(state)) {
                (state.equals(target.desiredState()) ? exact : accepted).add(face);
            }
        }
        exact.addAll(accepted);
        return exact;
    }

    /**
     * 这一下点击原版会放成什么:按这一次准星的命中点造放置上下文,问物品自己;放不出来(站不住、被挡着、不是落在这一格)是 null。
     * 只读,不动世界。
     */
    private BlockState simulate(BuildTaskRecord.Target target, BlockItem item, BlockHitResult hit) {
        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(item), hit);
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
     * 转向这一次点击的瞄点,交出准星落着的那一下;没落在要点的方块的那一面上(被挡着、被实体挡着、够不着)为 null。
     * 转头取整到鼠标像素,所以命中点是转完之后的朝向真打到的,不是理想的那一条线。
     */
    private BlockHitResult lookAt(Faces.Face face) {
        player.look().at(face.point());
        return player.mouse().pick() instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(face.clicked()) && hit.getDirection() == face.side() ? hit : null;
    }

    // ------------------------------------------------------------------
    // 直接写
    // ------------------------------------------------------------------

    /**
     * 照图直写这一格,带着原版的方块更新(与手放同一种:邻居当场按原版规则反应)。写不进去就什么都不算:{@code setBlock} 在超出
     * 建造高度时直接返回假、世界毫无变化,照样扣料会让材料凭空消失。
     *
     * <p>写下去之后让原版给这一格自己也复核一次({@code Block.updateFromNeighbourShapes}:六个邻居各自对它的形状说一句,火把没有
     * 依附就变成空气并掉落、栅栏与玻璃板按邻居连上)——手放时这一步在放置规则里,直接写的格绕过了放置规则,所以补在这里。
     * 结果以世界为准,留不留得住由调用方比对。
     *
     * <p>复核之前给方块一次"我被放下了"的回调({@code setPlacedBy}):命名牌、告示牌、部分方块实体靠它初始化,门/床/高草的回调写
     * 另一半(所以次半根本不进目标集)。回调拿到的是"她手里那件东西":有料单的格给单子上
     * 第一叠真货(带花纹的旗帜),不是一件同名的白货。
     */
    private boolean setDirectly(BuildTaskRecord.Target target, int cost) {
        BlockPos pos = target.pos();
        BlockState desired = target.desiredState();
        if (!player.level().setBlock(pos, desired, Block.UPDATE_ALL)) {
            return false;
        }
        try {
            desired.getBlock().setPlacedBy(player.level(), pos, desired, player,
                    new ItemStack(target.item()));
        } catch (RuntimeException ignored) {
            // 回调本是给"玩家手持物品放置"设计的,这里没有那个上下文,个别方块会在里面自己炸掉;放置本身已经成功,
            // 回调失败只影响那一格的附加数据,不该让整栋楼停工
        }
        for (int k = 0; k < cost; k++) {
            inv.consumeOne(target.item());
        }
        BlockState written = player.level().getBlockState(pos);
        Block.updateOrDestroy(written, Block.updateFromNeighbourShapes(written, player.level(), pos), player.level(), pos,
                Block.UPDATE_ALL);
        // 直写不经 BlockItem.place,放置记录在这里记:这一格(连回调补出的另一半)是她放的。手放那条路由 BlockItem 上的 Mixin 记
        PlacedBlocks.placedBy(player.serverLevel(), pos, player);
        return true;
    }
}
