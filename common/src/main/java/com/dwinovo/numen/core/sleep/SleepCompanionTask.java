package com.dwinovo.numen.core.sleep;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import com.mojang.datafixers.util.Either;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

public final class SleepCompanionTask extends AbstractCompanionTask<SleepTaskRecord> {
    private static final int SEARCH_RADIUS = 32;
    private static final double WALK_SPEED = 1.0D;
    private static final Set<Block> BED_BLOCKS = discoverBedBlocks();

    private BlockPos bedHead;
    private SleepOutcome outcome;

    public SleepCompanionTask(NumenPlayer player, SleepTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        if (player.isSleeping()) {
            outcome = SleepOutcome.verify(null, true);
            succeed();
            return;
        }

        bedHead = findNearestBedHead();
        if (bedHead == null) {
            fail("no bed found within " + SEARCH_RADIUS + " loaded blocks", FailureType.TARGET_LOST);
            return;
        }
        if (!withinVanillaSleepRange()) {
            nav = new PlayerNav(player, bedHead, WALK_SPEED, this::withinVanillaSleepRange);
        }
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }
        if (player.isSleeping()) {
            outcome = SleepOutcome.verify(null, true);
            return TaskState.SUCCESS;
        }
        if (!isCurrentBed()) {
            fail("the selected bed is no longer present", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }

        if (!withinVanillaSleepRange()) {
            if (nav == null) {
                nav = new PlayerNav(player, bedHead, WALK_SPEED, this::withinVanillaSleepRange);
            }
            PlayerNav.Status status = nav.tick();
            if (status == PlayerNav.Status.RUNNING) {
                return TaskState.RUNNING;
            }
            if (status == PlayerNav.Status.FAILED) {
                String reason = nav.failReason();
                FailureType type = nav.failType();
                stopNav();
                fail(reason == null || reason.isBlank() ? "could not reach the selected bed" : reason, type);
                return TaskState.FAILED;
            }
            stopNav();
            if (!withinVanillaSleepRange()) {
                fail("reached the bed path goal but remained outside vanilla sleep range", FailureType.STANCE_DUD);
                return TaskState.FAILED;
            }
        }

        return requestVanillaSleep();
    }

    private TaskState requestVanillaSleep() {
        Either<Player.BedSleepingProblem, Unit> result = player.startSleepInBed(bedHead);
        String rejection = result.left().map(SleepCompanionTask::problemMessage).orElse(null);
        outcome = SleepOutcome.verify(rejection, player.isSleeping());
        if (outcome.success()) {
            return TaskState.SUCCESS;
        }
        fail(outcome.message(), FailureType.UNKNOWN);
        return TaskState.FAILED;
    }

    private BlockPos findNearestBedHead() {
        for (BlockScanner.Hit hit : BlockScanner.findWithin(
            player.level(),
            player.blockPosition(),
            SEARCH_RADIUS,
            BED_BLOCKS
        )) {
            BlockPos head = normalizeHead(hit.pos(), hit.state());
            if (head != null && isBedHead(head)) {
                return head;
            }
        }
        return null;
    }

    private boolean isCurrentBed() {
        return bedHead != null && isBedHead(bedHead);
    }

    private boolean isBedHead(BlockPos pos) {
        BlockState state = player.level().getBlockState(pos);
        return state.getBlock() instanceof BedBlock
            && state.hasProperty(BedBlock.PART)
            && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    private boolean withinVanillaSleepRange() {
        if (!isCurrentBed()) {
            return false;
        }
        BlockState headState = player.level().getBlockState(bedHead);
        Direction facing = headState.getValue(BedBlock.FACING);
        BlockPos foot = bedHead.relative(facing.getOpposite());
        return withinVanillaSleepRange(bedHead) || withinVanillaSleepRange(foot);
    }

    private boolean withinVanillaSleepRange(BlockPos bedPart) {
        Vec3 center = Vec3.atBottomCenterOf(bedPart);
        return Math.abs(player.getX() - center.x()) <= 3.0D
            && Math.abs(player.getY() - center.y()) <= 2.0D
            && Math.abs(player.getZ() - center.z()) <= 3.0D;
    }

    private static BlockPos normalizeHead(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BedBlock)
            || !state.hasProperty(BedBlock.PART)
            || !state.hasProperty(BedBlock.FACING)) {
            return null;
        }
        if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return pos.immutable();
        }
        return pos.relative(state.getValue(BedBlock.FACING)).immutable();
    }

    private static Set<Block> discoverBedBlocks() {
        Set<Block> beds = new LinkedHashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof BedBlock) {
                beds.add(block);
            }
        }
        return Collections.unmodifiableSet(beds);
    }

    private static String problemMessage(Player.BedSleepingProblem problem) {
        Component message = problem.message();
        if (message == null || message.getString().isBlank()) {
            return "the vanilla server rejected the sleep request";
        }
        return message.getString();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("search_radius", SEARCH_RADIUS);
        data.put("sleeping", player.isSleeping());
        data.put("server_verified", outcome != null && outcome.success());
        if (bedHead != null) {
            data.put("bed", Map.of(
                "x", bedHead.getX(),
                "y", bedHead.getY(),
                "z", bedHead.getZ()
            ));
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return outcome == null ? "sleeping in bed (server verified)" : outcome.message();
    }

    @Override
    protected String timeoutMessage() {
        return "timed out while finding or reaching a bed";
    }

    @Override
    protected String cancelledMessage() {
        return "sleep task was interrupted";
    }
}
