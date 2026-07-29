package com.dwinovo.numen.core.follow;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

final class FollowStage7ATestSupport {

    private FollowStage7ATestSupport() {}

    static FollowState state(
            boolean enabled,
            boolean paused,
            Double stopOverride,
            Double startOverride) {
        return new FollowState(
                enabled,
                paused,
                FollowState.CURRENT_SCHEMA_VERSION,
                stopOverride,
                startOverride);
    }

    static FollowStateStore reload(FollowStateStore store) {
        return FollowStateStore.load(store.save(new CompoundTag()));
    }

    static FollowService.Subject subject(
            UUID companionUuid,
            boolean ownerPresent,
            boolean ownerOnline,
            boolean ownerValid,
            boolean sameDimension,
            OptionalDouble distance,
            long gameTime) {
        return new FollowService.Subject(
                companionUuid,
                "Numen",
                true,
                ownerPresent,
                ownerOnline,
                ownerValid,
                sameDimension,
                distance,
                gameTime);
    }

    static final class TrackingControl implements FollowRuntimeControl {
        private final UUID companionUuid;
        private final List<FollowReleaseReason> reasons = new ArrayList<>();
        private FollowRuntimeSnapshot snapshot;

        TrackingControl(UUID companionUuid, FollowRuntimeSnapshot snapshot) {
            this.companionUuid = companionUuid;
            this.snapshot = snapshot;
        }

        @Override
        public UUID companionUuid() {
            return companionUuid;
        }

        @Override
        public void release(FollowReleaseReason reason) {
            reasons.add(reason);
        }

        @Override
        public FollowRuntimeSnapshot snapshot(long currentGameTime) {
            return snapshot;
        }

        List<FollowReleaseReason> reasons() {
            return List.copyOf(reasons);
        }

        void snapshot(FollowRuntimeSnapshot value) {
            snapshot = value;
        }
    }
}
