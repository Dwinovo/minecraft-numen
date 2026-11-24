package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Hotbar;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.pathing.drive.Blockage.Hitch;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.ToolChoice;
import com.dwinovo.numen.api.entity.Faces;
import com.dwinovo.numen.api.entity.Replaceable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 做一件改动:挖掉一格、放下一块。身体站在哪由控制器管,这里只管手:把该用的东西拿到手上,朝那一格转一下(给看着她的人看的),
 * 指名那一格经 {@link Mouse} 动手——够得着就点得中——把它交回的结果记进实际账。开关门是 {@link DoorOpener}。
 *
 * <p>每刻调一次,直到 {@link #done} 答是。够不着的这一下鼠标不按:身体还在挪就下一刻再来,一直够不着由这一步的期限收场。
 */
final class Work {

    private final Rig rig;
    private final MoveKind move;
    /** 正在做的那一件;换了一件就重新拿工具。 */
    private Edit current;
    /** 接坠落的那桶水已经倒下、已经收回。 */
    private boolean poured;
    private boolean scooped;

    Work(Rig rig, MoveKind move) {
        this.rig = rig;
        this.move = move;
    }

    /** 正在做的那一件;还没动手为 null。 */
    Edit current() {
        return current;
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
            if (edit instanceof Edit.Dig dig) {
                // 先把挑中的工具拿到手上:按此刻的身体挑,与规划定价是同一个选择
                BlockState state = rig.world().getBlockState(dig.pos());
                rig.act(new ToolChoice(rig.snapshot()).take(state, rig.hotbar).orElse(null));
            }
        }
        return switch (edit) {
            case Edit.Dig dig -> dig(dig);
            case Edit.Place place -> place(place);
            case Edit.Door door -> DoorOpener.tick(rig, door);
            case Edit.Catch caught -> poured ? scoop(caught) : pour(caught);
        };
    }

    // ==================== 挖 ====================

    private Beat dig(Edit.Dig edit) {
        BlockPos pos = edit.pos();
        rig.look.at(Vec3.atCenterOf(pos));
        return switch (rig.dig(pos)) {
            case Mouse.Strike.Swinging s -> Beat.WORKED;
            case Mouse.Strike.Missed m -> Beat.IDLE;
            case Mouse.Strike.Broke broke -> {
                rig.ledger.dug(broke.pos(), broke.before());
                if (PathLog.debugging()) {
                    PathLog.debug("{} dug {} {}{}", rig.who, PathLog.pos(broke.pos()), PathLog.block(broke.before()),
                            broke.pos().equals(pos) ? "" : " (meant to dig " + PathLog.pos(pos) + ")");
                }
                yield Beat.WORKED;
            }
            case Mouse.Strike.Refused refused -> refused(rig, "dig", refused.pos(), refused.reason());
        };
    }

    // ==================== 放 ====================

    private Beat place(Edit.Place edit) {
        ServerPlayer body = rig.entity;
        BlockPos pos = edit.pos();
        Hotbar.Grip grip = rig.hotbar.grip(Stacks.Which.of(edit.block().asItem()));
        rig.act(grip.action());
        if (!grip.ready()) {
            return new Beat.Blocked(new Blockage(pos, rig.world().getBlockState(pos), move, null, Hitch.NO_MATERIALS));
        }
        // 贴着哪个邻格的哪一面放:规划的准入问的是同一个(有没有面可贴),放下去落在这一格才算
        Faces.Face face = Faces.against(rig.world(), pos, edit.block());
        if (face == null || !pos.equals(Replaceable.landing(rig.world(), face.clicked(), face.side(), edit.block()))) {
            return new Beat.Blocked(new Blockage(pos, rig.world().getBlockState(pos), move, null, Hitch.NO_FACE));
        }
        rig.look.at(Faces.point(rig.world(), face.clicked(), face.side()));
        // 点中的方块若自己会响应右键(箱子、门),按着潜行才是往上贴方块:按下的那一刻按着潜行,与搭桥的玩家一样
        boolean sneaking = body.isShiftKeyDown();
        body.setShiftKeyDown(true);
        Mouse.Use use = rig.use(face.clicked(), face.side());
        body.setShiftKeyDown(sneaking);
        return switch (use) {
            case Mouse.Use.Pressed pressed when !pressed.changes().isEmpty() -> {
                rig.ledger.used(pressed.changes());
                if (PathLog.debugging()) {
                    PathLog.debug("{} placed {} {}", rig.who, PathLog.pos(pos), changes(pressed));
                }
                yield Beat.WORKED;
            }
            case Mouse.Use.Refused refused -> refused(rig, "place", refused.pos(), refused.reason());
            case Mouse.Use.Pressed pressed -> Beat.IDLE;
            case Mouse.Use.Waiting waiting -> Beat.IDLE;
        };
    }

    // ==================== 接坠落的水 ====================

    /**
     * 下落途中往落点倒一桶水:水桶拿在手上,低头看着能贴的那一面(先落点脚下那块的顶面),右键——原版水桶在服务端顺着她的视线
     * 把水倒在碰上的那一面前面,所以要等它自己那条线({@link Mouse#itemRayOn})真落在那一面上才按。
     */
    private Beat pour(Edit.Catch caught) {
        ServerPlayer body = rig.entity;
        Hotbar.Grip grip = rig.hotbar.grip(Stacks.Which.of(Items.WATER_BUCKET));
        rig.act(grip.action());
        if (!grip.ready()) {
            return new Beat.Blocked(new Blockage(caught.pos(), rig.world().getBlockState(caught.pos()), move, null,
                    Hitch.NO_MATERIALS));
        }
        Faces.Face face = Faces.against(rig.world(), caught.pos(), Blocks.WATER);
        if (face == null) {
            return Beat.IDLE;
        }
        rig.look.at(Faces.point(rig.world(), face.clicked(), face.side()));
        BlockHitResult ray = rig.mouse.itemRayOn(ClipContext.Fluid.NONE, face.clicked());
        if (ray == null || ray.getDirection() != face.side()) {
            return Beat.IDLE;
        }
        return switch (rig.use(face.clicked(), face.side())) {
            case Mouse.Use.Pressed pressed when !pressed.changes().isEmpty() -> {
                rig.ledger.used(pressed.changes());
                poured = rig.world().getBlockState(caught.pos()).is(Blocks.WATER);
                PathLog.info("{} poured water to break the fall {} feet {} blocks above the landing{}", rig.who, changes(pressed),
                        PathLog.num(body.getY() - caught.pos().getY()), poured ? "" : ", the water did not land on the landing " + PathLog.pos(caught.pos()));
                yield Beat.WORKED;
            }
            case Mouse.Use.Refused refused -> refused(rig, "pour water", refused.pos(), refused.reason());
            default -> Beat.IDLE;
        };
    }

    /**
     * 落进水里之后把水收回桶里:空桶拿在手上,低头看着脚下这一格的水,右键——原版空桶在服务端顺着她的视线舀起碰到的第一格水源。
     */
    private Beat scoop(Edit.Catch caught) {
        ServerPlayer body = rig.entity;
        BlockPos pos = caught.pos();
        if (!rig.world().getBlockState(pos).is(Blocks.WATER)) {
            scooped = true;
            return Beat.WORKED;
        }
        Hotbar.Grip grip = rig.hotbar.grip(Stacks.Which.of(Items.BUCKET));
        rig.act(grip.action());
        if (!grip.ready()) {
            return new Beat.Blocked(new Blockage(pos, rig.world().getBlockState(pos), move, null, Hitch.NO_MATERIALS));
        }
        rig.look.at(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        // 空桶那条射线是认水源的(SOURCE_ONLY),和满桶那条不是同一种
        if (rig.mouse.itemRayOn(ClipContext.Fluid.SOURCE_ONLY, pos) == null) {
            return Beat.IDLE;
        }
        // 这一下点的是水底下那一块的顶面(水倒下去时贴的那一面),空桶沿视线舀的是它前面这一格水
        return switch (rig.use(pos.below(), Direction.UP)) {
            case Mouse.Use.Pressed pressed when !pressed.changes().isEmpty() -> {
                rig.ledger.used(pressed.changes());
                scooped = !rig.world().getBlockState(pos).is(Blocks.WATER);
                PathLog.info("{} took the water back {}{}", rig.who, changes(pressed), scooped ? "" : ", " + PathLog.pos(pos) + " is still water");
                yield Beat.WORKED;
            }
            case Mouse.Use.Refused refused -> refused(rig, "take water back", refused.pos(), refused.reason());
            default -> Beat.IDLE;
        };
    }

    /** 动手被拒:记一行,交出拒绝方自己的理由。 */
    static Beat refused(Rig rig, String what, BlockPos pos, Mouse.Refusal reason) {
        PathLog.info("{} refused: {} {} {}: {}", rig.who, what, PathLog.pos(pos), PathLog.block(rig.world().getBlockState(pos)),
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
}
