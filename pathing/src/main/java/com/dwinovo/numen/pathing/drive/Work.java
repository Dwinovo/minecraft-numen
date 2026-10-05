package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.body.Crosshair;
import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.pathing.drive.Blockage.Hitch;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.ToolChoice;
import com.dwinovo.numen.pathing.world.Faces;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 做一件改动:挖掉一格、放下一块。身体站在哪由控制器管,这里只管手和眼:把该用的东西拿到手上,转过去看瞄点,准星落上了才
 * 按键,经 {@link Effector} 动手,把它交回的结果记进实际账。开关门是 {@link DoorOpener}。
 *
 * <p>每刻调一次,直到 {@link #done} 答是。
 */
final class Work {

    /** 要挖、要放的那一格连续这么多刻看不见或点不中,就不再等。 */
    private static final int BLIND_LIMIT = 40;

    private final Rig rig;
    private final MoveKind move;
    /** 正在做的那一件;换了一件就重新拿工具、重新计看不见的刻数。 */
    private Edit current;
    private int blind;
    /** 接坠落的那桶水已经倒下、已经收回。 */
    private boolean poured;
    private boolean scooped;

    Work(Rig rig, MoveKind move) {
        this.rig = rig;
        this.move = move;
    }

    /** 这件改动做完了:挖的那一格原来的方块没了,放的那一格已经是那种方块,门已经翻过来,接坠落的水倒了又收回了。 */
    boolean done(Edit edit) {
        BlockState now = rig.world().getBlockState(edit.pos());
        return switch (edit) {
            case Edit.Dig dig -> now.getBlock() != dig.state().getBlock();
            case Edit.Place place -> now.is(place.block());
            case Edit.Door door -> DoorOpener.opened(door, now);
            case Edit.Catch caught -> scooped;
        };
    }

    /** 接坠落的水已经倒下了。 */
    boolean poured() {
        return poured;
    }

    /** 做这一件改动的这一刻。 */
    Beat tick(Edit edit) {
        if (edit != current) {
            current = edit;
            blind = 0;
            if (edit instanceof Edit.Dig dig) {
                // 先把挑中的工具拿到手上:按此刻的身体挑,与规划定价是同一个选择
                BlockState state = rig.world().getBlockState(dig.pos());
                rig.act(Hotbar.hold(rig.entity, new ToolChoice(rig.snapshot()).best(state).slot()).orElse(null));
            }
        }
        return switch (edit) {
            case Edit.Dig dig -> dig(dig);
            case Edit.Place place -> place(place);
            case Edit.Door door -> DoorOpener.tick(rig, door, hitch -> blind(door.pos(), hitch));
            case Edit.Catch caught -> poured ? scoop(caught) : pour(caught);
        };
    }

    // ==================== 挖 ====================

    private Beat dig(Edit.Dig edit) {
        ServerPlayer body = rig.entity;
        BlockPos pos = edit.pos();
        Vec3 point = Aim.point(body, pos);
        if (point == null) {
            rig.hands.release();
            return blind(pos, Hitch.OCCLUDED);
        }
        Aim.look(body, point);
        BlockHitResult hit = Crosshair.on(body, pos);
        if (hit == null) {
            rig.hands.release();
            return blind(pos, Hitch.OCCLUDED);
        }
        return switch (rig.dig(hit)) {
            case Effector.Strike.Swinging s -> Beat.WORKED;
            case Effector.Strike.Broke broke -> {
                rig.ledger.dug(broke.pos(), broke.before(), broke.pos().equals(pos) ? edit.permit() : null);
                if (PathLog.debugging()) {
                    PathLog.debug("{} 挖掉 {} {}{}", rig.who, PathLog.pos(broke.pos()), PathLog.block(broke.before()),
                            broke.pos().equals(pos) ? "" : "(要挖的是 " + PathLog.pos(pos) + ")");
                }
                yield Beat.WORKED;
            }
            case Effector.Strike.Refused refused -> refused(rig, "挖", refused.pos(), refused.reason());
        };
    }

    // ==================== 放 ====================

    private Beat place(Edit.Place edit) {
        ServerPlayer body = rig.entity;
        BlockPos pos = edit.pos();
        Hotbar.Grip grip = Hotbar.grip(body, edit.block().asItem());
        rig.act(grip.action());
        if (!grip.ready()) {
            return new Beat.Blocked(new Blockage(pos, rig.world().getBlockState(pos), move, null, Hitch.NO_MATERIALS));
        }
        Faces.Face face = Aim.face(body, pos, edit.block());
        if (face == null) {
            return blind(pos, Hitch.NO_FACE);
        }
        Aim.look(body, face.point());
        if (!(Crosshair.pick(body) instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(face.clicked()) || hit.getDirection() != face.side()) {
            return blind(pos, Hitch.NO_FACE);
        }
        // 点中的方块若自己会响应右键(箱子、门),按着潜行才是往上贴方块:按下的那一刻按着潜行,与搭桥的玩家一样
        boolean sneaking = body.isShiftKeyDown();
        body.setShiftKeyDown(true);
        Effector.Use use = rig.use(hit);
        body.setShiftKeyDown(sneaking);
        return switch (use) {
            case Effector.Use.Waiting w -> Beat.IDLE;
            case Effector.Use.Nothing n -> Beat.IDLE;
            case Effector.Use.Changed changed -> {
                rig.ledger.used(changed.changes(), pos, edit.permit());
                if (PathLog.debugging()) {
                    PathLog.debug("{} 放下 {} {}", rig.who, PathLog.pos(pos), changes(changed));
                }
                yield Beat.WORKED;
            }
            case Effector.Use.Refused refused -> refused(rig, "放", refused.pos(), refused.reason());
        };
    }

    // ==================== 接坠落的水 ====================

    /**
     * 下落途中往落点倒一桶水:水桶拿在手上,低头点落点脚下那块的顶面,右键——原版水桶顺着视线把水倒在点中的那一面前面,
     * 就是落点那一格。
     */
    private Beat pour(Edit.Catch caught) {
        ServerPlayer body = rig.entity;
        Hotbar.Grip grip = Hotbar.grip(body, Items.WATER_BUCKET);
        rig.act(grip.action());
        if (!grip.ready()) {
            return new Beat.Blocked(new Blockage(caught.pos(), rig.world().getBlockState(caught.pos()), move, null,
                    Hitch.NO_MATERIALS));
        }
        Faces.Face face = Aim.face(body, caught.pos(), Blocks.WATER);
        if (face == null) {
            return Beat.IDLE;
        }
        Aim.look(body, face.point());
        if (!(Crosshair.pick(body) instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(face.clicked())) {
            return Beat.IDLE;
        }
        return switch (rig.use(hit)) {
            case Effector.Use.Changed changed -> {
                rig.ledger.used(changed.changes(), caught.pos(), caught.permit());
                poured = rig.world().getBlockState(caught.pos()).is(Blocks.WATER);
                PathLog.info("{} 倒水接坠落 {} 脚离落点还有 {} 格{}", rig.who, changes(changed),
                        PathLog.num(body.getY() - caught.pos().getY()), poured ? "" : ",水没落在落点 " + PathLog.pos(caught.pos()));
                yield Beat.WORKED;
            }
            case Effector.Use.Refused refused -> refused(rig, "倒水", refused.pos(), refused.reason());
            default -> Beat.IDLE;
        };
    }

    /**
     * 落进水里之后把水收回桶里:空桶拿在手上,低头看着脚下这一格的水,右键——原版空桶顺着视线舀起碰到的第一格水源。
     */
    private Beat scoop(Edit.Catch caught) {
        ServerPlayer body = rig.entity;
        BlockPos pos = caught.pos();
        if (!rig.world().getBlockState(pos).is(Blocks.WATER)) {
            scooped = true;
            return Beat.WORKED;
        }
        Hotbar.Grip grip = Hotbar.grip(body, Items.BUCKET);
        rig.act(grip.action());
        if (!grip.ready()) {
            return new Beat.Blocked(new Blockage(pos, rig.world().getBlockState(pos), move, null, Hitch.NO_MATERIALS));
        }
        Aim.look(body, new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        if (!(Crosshair.pick(body) instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return Beat.IDLE;
        }
        return switch (rig.use(hit)) {
            case Effector.Use.Changed changed -> {
                rig.ledger.used(changed.changes(), pos, caught.permit());
                scooped = !rig.world().getBlockState(pos).is(Blocks.WATER);
                PathLog.info("{} 收回水 {}{}", rig.who, changes(changed), scooped ? "" : "," + PathLog.pos(pos) + " 还是水");
                yield Beat.WORKED;
            }
            case Effector.Use.Refused refused -> refused(rig, "收水", refused.pos(), refused.reason());
            default -> Beat.IDLE;
        };
    }

    /** 动手被拒:记一行,交出拒绝方自己的理由。 */
    static Beat refused(Rig rig, String what, BlockPos pos, Object reason) {
        PathLog.info("{} 动手被拒 {} {} {}:{}", rig.who, what, PathLog.pos(pos), PathLog.block(rig.world().getBlockState(pos)),
                reason);
        return new Beat.Denied(pos, reason);
    }

    /** 右键之后变了的几格:{@code 格 原来 -> 现在}。 */
    static String changes(Effector.Use.Changed changed) {
        StringBuilder out = new StringBuilder();
        for (Effector.Change c : changed.changes()) {
            out.append(out.isEmpty() ? "" : "; ").append(PathLog.pos(c.pos())).append(' ').append(PathLog.block(c.before()))
                    .append(" -> ").append(PathLog.block(c.after()));
        }
        return out.toString();
    }

    /** 看不见、点不中:等一阵(身体还在挪、转头还没到),这一件改动累计如此太久就交出原因。 */
    private Beat blind(BlockPos pos, Hitch hitch) {
        if (++blind < BLIND_LIMIT) {
            return Beat.IDLE;
        }
        return new Beat.Blocked(new Blockage(pos, rig.world().getBlockState(pos), move, null, hitch));
    }
}
