package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Hotbar;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.pathing.drive.Blockage.Hitch;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.ToolChoice;
import com.dwinovo.numen.api.entity.Faces;

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
 * 按键,经 {@link Mouse} 动手,把它交回的结果记进实际账。开关门是 {@link DoorOpener}。
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
                rig.act(new ToolChoice(rig.snapshot()).take(state, rig.hotbar).orElse(null));
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
        Vec3 point = rig.look.point(pos);
        if (point == null) {
            rig.mouse.release();
            return blind(pos, Hitch.OCCLUDED);
        }
        rig.look.at(point);
        if (rig.mouse.on(pos) == null) {
            rig.mouse.release();
            return blind(pos, Hitch.OCCLUDED);
        }
        return switch (rig.dig()) {
            case Mouse.Strike.Swinging s -> Beat.WORKED;
            case Mouse.Strike.Missed m -> blind(pos, Hitch.OCCLUDED);
            case Mouse.Strike.Broke broke -> {
                rig.ledger.dug(broke.pos(), broke.before(), broke.pos().equals(pos) ? edit.permit() : null);
                if (PathLog.debugging()) {
                    PathLog.debug("{} 挖掉 {} {}{}", rig.who, PathLog.pos(broke.pos()), PathLog.block(broke.before()),
                            broke.pos().equals(pos) ? "" : "(要挖的是 " + PathLog.pos(pos) + ")");
                }
                yield Beat.WORKED;
            }
            case Mouse.Strike.Refused refused -> refused(rig, "挖", refused.pos(), refused.reason());
        };
    }

    // ==================== 放 ====================

    private Beat place(Edit.Place edit) {
        ServerPlayer body = rig.entity;
        BlockPos pos = edit.pos();
        Hotbar.Grip grip = rig.hotbar.grip(edit.block().asItem());
        rig.act(grip.action());
        if (!grip.ready()) {
            return new Beat.Blocked(new Blockage(pos, rig.world().getBlockState(pos), move, null, Hitch.NO_MATERIALS));
        }
        Faces.Face face = rig.look.face(pos, edit.block());
        if (face == null) {
            return blind(pos, Hitch.NO_FACE);
        }
        rig.look.at(face.point());
        if (!(rig.mouse.pick() instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(face.clicked()) || hit.getDirection() != face.side()) {
            return blind(pos, Hitch.NO_FACE);
        }
        // 点中的方块若自己会响应右键(箱子、门),按着潜行才是往上贴方块:按下的那一刻按着潜行,与搭桥的玩家一样
        boolean sneaking = body.isShiftKeyDown();
        body.setShiftKeyDown(true);
        Mouse.Use use = rig.use();
        body.setShiftKeyDown(sneaking);
        return switch (use) {
            case Mouse.Use.Pressed pressed when !pressed.changes().isEmpty() -> {
                rig.ledger.used(pressed.changes(), pos, edit.permit());
                if (PathLog.debugging()) {
                    PathLog.debug("{} 放下 {} {}", rig.who, PathLog.pos(pos), changes(pressed));
                }
                yield Beat.WORKED;
            }
            case Mouse.Use.Refused refused -> refused(rig, "放", refused.pos(), refused.reason());
            case Mouse.Use.Pressed pressed -> Beat.IDLE;
            case Mouse.Use.Waiting waiting -> Beat.IDLE;
        };
    }

    // ==================== 接坠落的水 ====================

    /**
     * 下落途中往落点倒一桶水:水桶拿在手上,低头点落点脚下那块的顶面,右键——原版水桶顺着视线把水倒在点中的那一面前面,
     * 就是落点那一格。
     */
    private Beat pour(Edit.Catch caught) {
        ServerPlayer body = rig.entity;
        Hotbar.Grip grip = rig.hotbar.grip(Items.WATER_BUCKET);
        rig.act(grip.action());
        if (!grip.ready()) {
            return new Beat.Blocked(new Blockage(caught.pos(), rig.world().getBlockState(caught.pos()), move, null,
                    Hitch.NO_MATERIALS));
        }
        Faces.Face face = rig.look.face(caught.pos(), Blocks.WATER);
        if (face == null) {
            return Beat.IDLE;
        }
        rig.look.at(face.point());
        if (!(rig.mouse.pick() instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(face.clicked())) {
            return Beat.IDLE;
        }
        return switch (rig.use()) {
            case Mouse.Use.Pressed pressed when !pressed.changes().isEmpty() -> {
                rig.ledger.used(pressed.changes(), caught.pos(), caught.permit());
                poured = rig.world().getBlockState(caught.pos()).is(Blocks.WATER);
                PathLog.info("{} 倒水接坠落 {} 脚离落点还有 {} 格{}", rig.who, changes(pressed),
                        PathLog.num(body.getY() - caught.pos().getY()), poured ? "" : ",水没落在落点 " + PathLog.pos(caught.pos()));
                yield Beat.WORKED;
            }
            case Mouse.Use.Refused refused -> refused(rig, "倒水", refused.pos(), refused.reason());
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
        Hotbar.Grip grip = rig.hotbar.grip(Items.BUCKET);
        rig.act(grip.action());
        if (!grip.ready()) {
            return new Beat.Blocked(new Blockage(pos, rig.world().getBlockState(pos), move, null, Hitch.NO_MATERIALS));
        }
        rig.look.at(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        if (!(rig.mouse.pick() instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return Beat.IDLE;
        }
        return switch (rig.use()) {
            case Mouse.Use.Pressed pressed when !pressed.changes().isEmpty() -> {
                rig.ledger.used(pressed.changes(), pos, caught.permit());
                scooped = !rig.world().getBlockState(pos).is(Blocks.WATER);
                PathLog.info("{} 收回水 {}{}", rig.who, changes(pressed), scooped ? "" : "," + PathLog.pos(pos) + " 还是水");
                yield Beat.WORKED;
            }
            case Mouse.Use.Refused refused -> refused(rig, "收水", refused.pos(), refused.reason());
            default -> Beat.IDLE;
        };
    }

    /** 动手被拒:记一行,交出拒绝方自己的理由。 */
    static Beat refused(Rig rig, String what, BlockPos pos, Mouse.Refusal reason) {
        PathLog.info("{} 动手被拒 {} {} {}:{}", rig.who, what, PathLog.pos(pos), PathLog.block(rig.world().getBlockState(pos)),
                reason);
        return new Beat.Denied(pos, reason);
    }

    /** 右键之后变了的几格:{@code 格 原来 -> 现在}。 */
    static String changes(Mouse.Use.Pressed pressed) {
        StringBuilder out = new StringBuilder();
        for (Mouse.Change c : pressed.changes()) {
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
