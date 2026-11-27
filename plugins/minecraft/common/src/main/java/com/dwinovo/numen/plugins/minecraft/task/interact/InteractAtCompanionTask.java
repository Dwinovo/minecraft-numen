package com.dwinovo.numen.plugins.minecraft.task.interact;
import com.dwinovo.numen.api.permission.GatedAct.Permit;
import com.dwinovo.numen.api.permission.GatedAct.PermitState;
import com.dwinovo.numen.plugins.minecraft.task.MouseButton;

import com.dwinovo.numen.api.task.TaskState;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.plugins.minecraft.FailureType;
import com.dwinovo.numen.plugins.minecraft.act.Interaction;
import com.dwinovo.numen.plugins.minecraft.nav.NavText;
import com.dwinovo.numen.plugins.minecraft.nav.Terrain;
import com.dwinovo.numen.plugins.minecraft.task.base.InReachTask;
import com.dwinovo.numen.plugins.minecraft.task.base.Precondition;
import com.dwinovo.numen.plugins.minecraft.task.move.Arrival;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * {@code minecraft.use.block} / {@code minecraft.use.hit} / {@code use item} on the player body — the native click on a named cell
 * (or on nothing in particular). Out of reach of the cell it first walks to where the hand reaches it ({@link #approach}),
 * then presses.
 *
 * <p>左键是一次纯按键:按在指名的那一格上({@link NumenPlayer#mouse}),手上是什么就用什么,点一下就松手(一下就碎的方块碎了,别的
 * 只是开了个头)——不换工具、不挪步。那一格是空气或流体就没有可点的,照实说。挖东西(挑工具、挖到碎)是 {@code minecraft.work.dig} 的事。
 *
 * <p>右键同样是一次纯按键:按在指名的那一格朝着她的那一面上({@link com.dwinovo.numen.api.entity.Mouse#aim}),够得着就点得中,
 * 中间隔着什么不问:激活方块,方块没接就落到手里的东西自用;没指名哪一格就只用手里的东西(扔、拉弓)。按之前朝点的那一点转过去:
 * 给看着她的人看,也让手里的东西自己沿视线打的那一条(桶、船)朝着这一格。The mouse model is the two record fields
 * {@code button} (left/right) × {@code holdTicks} (tap/hold).
 */
public final class InteractAtCompanionTask extends InReachTask<InteractAtTaskRecord, com.dwinovo.numen.plugins.minecraft.tools.Clicks.Clicked> {

    private Interaction interaction;
    private long holdUntil = -1;       // game tick to release a fixed-duration hold (holdTicks > 0)
    private String successMsg = "done";
    // A right-click that activated a real block (a station's GUI): captured so the
    // result names it — whether that station is worth a note is hers to decide.
    private net.minecraft.core.BlockPos activatedBlock;
    private String activatedBlockId;
    public InteractAtCompanionTask(NumenPlayer player, InteractAtTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        // If an item to use was named, fail fast unless a stack of that kind is where the grip reaches (backpack, off hand).
        return List.of(() -> r.which == null || player.hotbar().count(r.which) > 0 ? null
                : new Precondition.Failure(
                        "don't have " + BuiltInRegistries.ITEM.getKey(r.which.item()).getPath() + " to use",
                        FailureType.NO_MATERIAL));
    }

    /** 走到手够得着那一格的地方({@link Arrival#use})。 */
    @Override
    protected Arrival approach() {
        return Arrival.use(r.aim);
    }

    @Override
    protected boolean reached() {
        return r.aim == null || withinReach();
    }

    @Override
    protected TaskState act() {
        // Name what gets pressed once we're in position, then drive the action.
        if (interaction == null) {
            if (r.which != null) {
                player.hotbar().grip(r.which);
            }
            if (button() == Interaction.Button.ATTACK && !Terrain.of(player).clickable(r.aim)) {
                // 左键点空气或流体:那里没有可点的
                successMsg = "nothing to hit at " + aimLabel() + " (" + NavText.name(player.level().getBlockState(r.aim))
                        + ")";
                return TaskState.SUCCESS;
            }
            // 指名的那一格落成的那一下(朝着她的那一面);没指名哪一格就是只用手里的东西
            HitResult hit = r.aim != null ? player.mouse().aim(r.aim, null)
                    : BlockHitResult.miss(player.getEyePosition(), Direction.UP, player.blockPosition());
            if (r.aim != null) {
                // 先转过去:下面预问权限时,手里的东西自己沿视线打的那一条(桶)已经朝着这一格
                player.look().at(hit.getLocation());
            }
            // A consumable / ender pearl used in the AIR is body-bound (would feed or teleport the
            // fake player) — refuse even when it's just whatever happened to be in hand.
            if (button() == Interaction.Button.USE && hit.getType() == HitResult.Type.MISS) {
                String reason = InteractAtTaskRecord.bodyBoundReason(player.getMainHandItem().getItem());
                if (reason != null) {
                    fail(reason, FailureType.UNKNOWN);
                    return TaskState.FAILED;
                }
            }
            // 按下去之前:这一下要做的事交给权限层(`Mouse.intents`:鼠标在动手那一刻问的是同一张单)。不许就带着理由收场,
            // 要问就站着等主人
            List<com.dwinovo.numen.api.permission.Action> proposed =
                    player.mouse().intents(hit, button() == Interaction.Button.ATTACK);
            if (!proposed.isEmpty()) {
                List<Permit> permits = gate().permitAll(proposed);
                for (int i = 0; i < permits.size(); i++) {
                    if (permits.get(i).state() == PermitState.REFUSED) {
                        fail("cannot " + proposed.get(i).describe() + ": " + permits.get(i).refusal(),
                                FailureType.REFUSED);
                        return TaskState.FAILED;
                    }
                }
                if (permits.stream().anyMatch(p -> p.state() == PermitState.WAITING)) {
                    player.controls().stop();
                    return TaskState.RUNNING;
                }
            }
            // A right-click landing on a block activates it (opens a station's GUI,
            // flips a switch, …). Capture what we touched so the receipt can name it:
            // she reads it and decides for herself whether to remember the place.
            if (button() == Interaction.Button.USE && r.aim != null) {
                activatedBlock = r.aim;
                activatedBlockId = BuiltInRegistries.BLOCK
                        .getKey(player.level().getBlockState(activatedBlock).getBlock()).getPath();
            }
            // 兜底开关:身体约束物品(食物、末影珍珠)在任何一只手上都不落到物品
            // 自用——否则点一块石头没反应,同一次按键会把她自己喂了或传送走。
            boolean fallthroughOk =
                    InteractAtTaskRecord.bodyBoundReason(player.getMainHandItem().getItem()) == null
                    && InteractAtTaskRecord.bodyBoundReason(player.getOffhandItem().getItem()) == null;
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks, fallthroughOk, r.sneak);
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }

        // A fixed-duration hold ends when its window elapses: release the button.
        if (holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.stop();
            successMsg = describeDone() + settle();
            return TaskState.SUCCESS;
        }
        return switch (interaction.tick()) {
            case DONE -> {
                successMsg = describeDone() + settle();
                yield TaskState.SUCCESS;
            }
            case FAILED -> {
                fail(interaction.failReason(), interaction.failType());
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    private Interaction.Button button() {
        return r.button == MouseButton.LEFT
                ? Interaction.Button.ATTACK : Interaction.Button.USE;
    }

    /**
     * 够得着那一格就在原地按:问的是鼠标按下去时问的同一个({@link com.dwinovo.numen.api.entity.Mouse#reaches(net.minecraft.core.BlockPos)},
     * 坐在船上、浮在水里也一样问得出);够不着才走过去({@link #approach})。一旦按下去就不再问(按住的几刻里身体可能挪动)。
     */
    private boolean withinReach() {
        if (interaction != null) {
            return true;
        }
        return bodySettled() && player.mouse().reaches(r.aim);
    }

    private String aimLabel() {
        return r.aim.getX() + "," + r.aim.getY() + "," + r.aim.getZ();
    }

    private String describeDone() {
        String verb = r.button == MouseButton.LEFT ? "left-clicked" : "right-clicked";
        String what = r.aim != null ? " " + aimLabel() : " (forward)";
        return verb + what + (r.sneak ? " while sneaking" : "");
    }

    /**
     * 收尾那半句:这一下游戏接没接。不比对世界——接了不等于留下了看得见的变化(船可以吃掉点击却因站位碰撞一无所成),
     * 之后世界什么样她自己看。
     */
    private String settle() {
        return com.dwinovo.numen.plugins.minecraft.tools.Clicks.said(interaction);
    }

    /**
     * 这一下打开了界面(箱子、熔炉、手里的东西自己的界面)就当场合上,回执与交回的值都不提它:她面前没有界面。派这件活的要接着用
     * 那个界面时({@link InteractAtTaskRecord#keepsWindow})留着。
     */
    @Override
    protected void closeBooks() {
        if (!r.keepsWindow && interaction != null && interaction.opened()
                && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    /** Release the interaction, then the nav + overlay (base default). */
    @Override
    protected void cleanup() {
        if (interaction != null) interaction.stop();
        super.cleanup();
    }

    /** 按了哪个键、瞄哪一格、游戏接没接。 */
    @Override
    protected com.dwinovo.numen.plugins.minecraft.tools.Clicks.Clicked value() {
        // 点开的那个工位(与它确切的位置,比瞄的那一格准):她只记得住我们告诉过她的地方
        java.util.Optional<com.dwinovo.numen.api.sdk.BlockAt> station = activatedBlock == null ? java.util.Optional.empty()
                : java.util.Optional.of(new com.dwinovo.numen.api.sdk.BlockAt(activatedBlockId, activatedBlock));
        return com.dwinovo.numen.plugins.minecraft.tools.Clicks.result(
                r.button == MouseButton.LEFT ? com.dwinovo.numen.plugins.minecraft.tools.Clicks.Button.LEFT
                        : com.dwinovo.numen.plugins.minecraft.tools.Clicks.Button.RIGHT,
                r.aim, null, station, interaction != null && interaction.accepted());
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out before interacting at " + (r.aim != null ? aimLabel() : "forward");
    }

    @Override
    protected String cancelledMessage() {
        return r.getToolName() + " interrupted";
    }
}
