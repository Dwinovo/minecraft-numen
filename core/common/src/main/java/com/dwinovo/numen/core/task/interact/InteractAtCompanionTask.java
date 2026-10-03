package com.dwinovo.numen.core.task.interact;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.core.PlayerInv;

import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.act.PressReceipt;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.task.move.GotoReminders;
import com.dwinovo.numen.core.task.base.InReachTask;
import com.dwinovo.numen.pathing.body.Crosshair;
import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code numen.use.block} / {@code use item} on the player body — the point-aimed native interaction (BLOCK + AIR).
 * It does not travel: the body must already be within reach of the aim (if one is given).
 *
 * <p>左键是一次纯按键:朝那一格的中心看过去,准星落在谁就按谁({@link Crosshair#pick}),手上是什么就用什么,点一下就松手
 * (一下就碎的方块碎了,别的只是开了个头)——不换工具、不清挡着的、不挪步。准星落在别的格(高草、树叶)或实体上,按的就是它,回执照实说。
 * 挖东西(挑工具、清开视线、捡掉落)是 {@code numen.work.dig} 的事。
 *
 * <p>右键同样是一次纯按键:可点的目标看向它看得见的一面({@link Aim#use},与 {@code numen.move.to(…, {arrive = "use"})} 同一个视线函数),
 * 准星落在谁就点谁({@link Interaction#forHit}):激活方块,或——对着空气——用手里的东西(扔、吃、拉弓)。视线上挡着的(箱子前的
 * 高草)不清,点到的就是它,回执照实说,下一步写出 {@code numen.work.dig} 挖掉它或从另一面点。The mouse model is the two record fields
 * {@code button} (left/right) × {@code holdTicks} (tap/hold).
 */
public final class InteractAtCompanionTask extends InReachTask<InteractAtTaskRecord> {

    private Interaction interaction;
    /** 按键前的世界快照,收尾时对账出"真发生了什么"(见 {@link PressReceipt})。 */
    private PressReceipt receipt;
    private java.util.List<String> changes = List.of();
    private long holdUntil = -1;       // game tick to release a fixed-duration hold (holdTicks > 0)
    private String successMsg = "done";
    // A right-click that activated a real block (a station's GUI): captured so the
    // result names it — whether that station is worth a note is hers to decide.
    private net.minecraft.core.BlockPos activatedBlock;
    private String activatedBlockId;
    /** 准星没落在瞄的那一格上、落在了别的东西上:回执里说按的是谁;落在瞄的那一格上为 null。 */
    private String landedElsewhere;

    public InteractAtCompanionTask(NumenPlayer player, InteractAtTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        // If an item to use was named, fail fast unless we actually carry it.
        return List.of(() -> r.item == null || PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                : new Precondition.Failure(
                        "don't have " + BuiltInRegistries.ITEM.getKey(r.item).getPath() + " to use",
                        FailureType.NO_MATERIAL));
    }

    @Override
    protected net.minecraft.core.BlockPos target() {
        return r.aim;
    }

    @Override
    protected boolean reached() {
        return r.aim == null || withinReach();
    }

    @Override
    protected TaskState act() {
        // Resolve the crosshair once we're in position, then drive the action.
        if (interaction == null) {
            if (r.item != null) {
                Hotbar.grip(player, r.item);
            }
            // 右键点可点的目标:看向它看得见的那一面(与 numen.move.to 的 arrive = "use" 同一个视线函数),哪一面都看不见就看格心。
            // 左键、空气与流体都看格心;对水面右键的原版含义正是"射线穿过去,物品自己找水"(桶、船),落点不另说。
            // 两个键都是纯按键:准星落在谁就按谁,挡在前面的不清,回执照实说
            boolean clickable = r.aim != null && Terrain.of(player).clickable(r.aim);
            if (r.aim != null) {
                com.dwinovo.numen.pathing.world.Sight.Trace seen =
                        button() == Interaction.Button.USE && clickable ? Aim.use(player, r.aim) : null;
                InputDriver.lookAt(player, seen != null ? seen.point() : Vec3.atCenterOf(r.aim));
            }
            HitResult hit = Crosshair.pick(player);
            if (r.aim != null && (button() == Interaction.Button.ATTACK || clickable)) {
                landedElsewhere = elsewhere(hit);
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
            // 按下去之前:这一下要做的事交给权限层(见 proposedActions)。不许就带着理由收场,
            // 要问就站着等主人
            List<com.dwinovo.numen.permission.Action> proposed = proposedActions(hit);
            if (!proposed.isEmpty()) {
                List<Permit> permits = permitAll(proposed);
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
            if (button() == Interaction.Button.USE && hit instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                activatedBlock = bhr.getBlockPos();
                activatedBlockId = BuiltInRegistries.BLOCK
                        .getKey(player.level().getBlockState(activatedBlock).getBlock()).getPath();
            }
            // 兜底开关:身体约束物品(食物、末影珍珠)在任何一只手上都不落到物品
            // 自用——否则点一块石头没反应,同一次按键会把她自己喂了或传送走。
            boolean fallthroughOk =
                    InteractAtTaskRecord.bodyBoundReason(player.getMainHandItem().getItem()) == null
                    && InteractAtTaskRecord.bodyBoundReason(player.getOffhandItem().getItem()) == null;
            receipt = PressReceipt.before(player, r.aim);
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks, fallthroughOk, r.sneak);
            if (interaction == null) {       // left-click on air — a swing, nothing to do
                successMsg = "nothing under the aim (left-click in the air)";
                return TaskState.SUCCESS;
            }
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

    /**
     * 准星落点上这一下要做的事:左键是挖、打;右键是右键方块、右键实体。右键方块时方块不吃这一下就轮到
     * 手里的东西,两只手里会往世界里放东西的({@link Interaction#placementOf})也一并算上。
     */
    private List<com.dwinovo.numen.permission.Action> proposedActions(HitResult hit) {
        boolean left = button() == Interaction.Button.ATTACK;
        if (hit instanceof net.minecraft.world.phys.BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            var state = player.level().getBlockState(bh.getBlockPos());
            if (left) {
                return List.of(com.dwinovo.numen.permission.Action.breakBlock(bh.getBlockPos(), state));
            }
            List<com.dwinovo.numen.permission.Action> out = new java.util.ArrayList<>();
            out.add(com.dwinovo.numen.permission.Action.useBlock(bh.getBlockPos(), state));
            for (var hand : net.minecraft.world.InteractionHand.values()) {
                var placing = Interaction.placementOf(player.level(), bh, player.getItemInHand(hand));
                if (placing != null) {
                    out.add(placing);
                }
            }
            return out;
        }
        if (hit instanceof net.minecraft.world.phys.EntityHitResult eh) {
            return List.of(left ? com.dwinovo.numen.permission.Action.attack(eh.getEntity())
                    : com.dwinovo.numen.permission.Action.useEntity(eh.getEntity()));
        }
        return List.of();
    }


    private Interaction.Button button() {
        return r.button == MouseButton.LEFT
                ? Interaction.Button.ATTACK : Interaction.Button.USE;
    }

    private boolean withinReach() {
        return bodySettled() && player.canInteractWithBlock(r.aim, 0.0);
    }

    private String aimLabel() {
        return r.aim.getX() + "," + r.aim.getY() + "," + r.aim.getZ();
    }

    private String describeDone() {
        String verb = r.button == MouseButton.LEFT ? "left-clicked" : "right-clicked";
        String what = landedElsewhere != null ? " " + landedElsewhere
                : r.aim != null ? " " + aimLabel() : " (forward)";
        return verb + what + (r.sneak ? " while sneaking" : "");
    }

    /**
     * 准星落着的不是瞄的那一格时,回执里说按的是谁:{@code short_grass at 1,65,2 — the crosshair landed there, not on
     * 1,64,2};右键落在别的格上,再写出够到它的两条路:挖掉挡着的那一格,或走到看得见它另一面的地方。落在瞄的那一格上
     * (或什么都没落着)为 null。
     */
    private String elsewhere(HitResult hit) {
        if (hit instanceof net.minecraft.world.phys.BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            var pos = bh.getBlockPos();
            if (pos.equals(r.aim)) {
                return null;
            }
            String landed = NavText.name(player.level().getBlockState(pos)) + " at " + pos.getX() + "," + pos.getY()
                    + "," + pos.getZ() + " — the crosshair landed there, not on " + aimLabel();
            return button() == Interaction.Button.ATTACK ? landed
                    : landed + ". To click " + aimLabel() + ": `numen.work.dig(" + Shapes.literal(pos)
                            + ")` clears it out of the way, or "
                            + GotoReminders.call(r.aim, "arrive = \"use\"")
                            + " stands where another face of it is in sight";
        }
        if (hit instanceof net.minecraft.world.phys.EntityHitResult eh) {
            return eh.getEntity().getName().getString() + " (entity " + eh.getEntity().getId()
                    + ") — the crosshair landed on it, not on " + aimLabel();
        }
        return null;
    }

    /**
     * 收尾对账:回执只报事实,不判成败。"按键被消费"不等于"发生了什么"——
     * 船可以吃掉点击却因站位碰撞一无所成,此前这里会报一句裸的成功,模型
     * 就当船已经放下了。什么都没变时明说,她自己决定挪个位置再试还是放弃。
     */
    private String settle() {
        changes = receipt == null ? List.of() : receipt.diff(player);
        if (changes.isEmpty()) {
            return " — but nothing visibly changed (hands, aimed block, nearby entities all "
                    + "as before). If you expected an effect, reposition or rethink.";
        }
        return " — " + String.join("; ", changes);
    }

    /** Release the interaction, then the nav + overlay (base default). */
    @Override
    protected void cleanup() {
        if (interaction != null) interaction.stop();
        super.cleanup();
    }

    /**
     * 右键打开了一个界面(箱子、熔炉、机器),交回的就是那个 Window,和 {@code numen.gui.view()} 读到的一样,外加点开它的那一格;
     * 别的点击交回按了哪个键、瞄哪一格、变了什么。
     */
    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        boolean opened = r.button == MouseButton.RIGHT && player.containerMenu != player.inventoryMenu;
        if (opened) {
            data.putAll(com.dwinovo.numen.core.tools.GuiOps.window(player).data());
        } else {
            data.put("button", r.button == MouseButton.LEFT ? "left" : "right");
            if (r.aim != null) {
                data.put("aim", Shapes.pos(r.aim));
            }
            if (!changes.isEmpty()) {
                data.put("changes", changes);
            }
        }
        // Report the activated station (and its exact position, authoritative over the
        // raw aim): she can only note a place we told her about.
        if (activatedBlock != null) {
            com.google.gson.JsonObject block = Shapes.pos(activatedBlock);
            com.google.gson.JsonObject station = new com.google.gson.JsonObject();
            station.addProperty("name", activatedBlockId);
            station.add("pos", block);
            data.put("block", station);
        }
        return data;
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
