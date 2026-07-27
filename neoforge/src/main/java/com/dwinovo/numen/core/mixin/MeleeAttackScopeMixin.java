package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.combat.CombatArea;
import com.dwinovo.numen.core.combat.CombatAreaRegistry;
import com.dwinovo.numen.core.combat.CombatAreaReport;
import com.dwinovo.numen.core.combat.CombatDeathEvents;
import com.dwinovo.numen.core.task.MeleeAttackTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.task.MeleeAttackCompanionTask")
public abstract class MeleeAttackScopeMixin {
    @Shadow private LivingEntity target;

    @Unique private CombatArea numen$combatArea;
    @Unique private NumenPlayer numen$player;
    @Unique private MeleeAttackTaskRecord numen$record;
    @Unique private Set<Integer> numen$outOfScope;
    @Unique private Map<Integer, UUID> numen$targetIdentities;
    @Unique private long numen$taskStart;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void numen$captureCombatArea(
        NumenPlayer player,
        MeleeAttackTaskRecord record,
        CallbackInfo callback
    ) {
        this.numen$player = player;
        this.numen$record = record;
        this.numen$outOfScope = new TreeSet<>();
        this.numen$combatArea = CombatAreaRegistry.resolve(player, record.entityIds);
        this.numen$targetIdentities = new HashMap<>();
        this.numen$taskStart = player.level().getGameTime();
        for (int entityId : record.entityIds) {
            Entity entity = player.level().getEntity(entityId);
            if (entity instanceof LivingEntity living) {
                this.numen$targetIdentities.put(entityId, living.getUUID());
            }
        }
    }

    @Inject(method = "onTick", at = @At("HEAD"))
    private void numen$reconcileRealDeaths(CallbackInfoReturnable<TaskState> callback) {
        boolean clearCurrent = false;
        for (int entityId : this.numen$record.entityIds) {
            if (this.numen$record.terminal(entityId)) {
                continue;
            }
            UUID entityUuid = this.numen$targetIdentities.get(entityId);
            if (entityUuid == null || !CombatDeathEvents.diedSince(
                this.numen$player.level(),
                entityId,
                entityUuid,
                this.numen$taskStart
            )) {
                continue;
            }
            this.numen$record.defeated(entityId);
            clearCurrent |= this.target != null && this.target.getId() == entityId;
        }
        if (clearCurrent) {
            ((MeleeAttackTaskAccessor) this).numen$clearTarget();
        }
    }

    @Inject(method = "selectTarget", at = @At("HEAD"))
    private void numen$excludeTargetsOutsideArea(CallbackInfoReturnable<LivingEntity> callback) {
        for (int entityId : this.numen$record.entityIds) {
            if (this.numen$record.terminal(entityId)) {
                continue;
            }
            Entity entity = this.numen$targetEntity(entityId);
            if (entity instanceof LivingEntity living
                && !living.isDeadOrDying()
                && !living.isRemoved()
                && !this.numen$contains(living)) {
                this.numen$markOutOfScope(entityId);
            }
        }
    }

    @Inject(method = "tickTarget", at = @At("HEAD"), cancellable = true)
    private void numen$stopChasingOutsideArea(CallbackInfoReturnable<TaskState> callback) {
        if (this.target == null
            || this.target.isDeadOrDying()
            || this.target.isRemoved()
            || this.numen$contains(this.target)) {
            return;
        }
        this.numen$markOutOfScope(this.target.getId());
        ((MeleeAttackTaskAccessor) this).numen$clearTarget();
        callback.setReturnValue(TaskState.RUNNING);
    }

    @Inject(method = "resultData", at = @At("RETURN"), cancellable = true)
    private void numen$reportCombatArea(CallbackInfoReturnable<Map<String, Object>> callback) {
        Map<String, Object> result = new LinkedHashMap<>(callback.getReturnValue());
        result.put("combat_area", CombatAreaReport.describe(this.numen$combatArea));
        result.put("out_of_scope_entity_ids", Set.copyOf(this.numen$outOfScope));
        callback.setReturnValue(result);
    }

    @Inject(
        method = {"successMessage", "timeoutMessage", "cancelledMessage"},
        at = @At("RETURN"),
        cancellable = true
    )
    private void numen$reportSkippedTargets(CallbackInfoReturnable<String> callback) {
        callback.setReturnValue(CombatAreaReport.appendSkipped(callback.getReturnValue(), this.numen$outOfScope));
    }

    @Unique
    private Entity numen$targetEntity(int entityId) {
        LivingEntity current = this.target;
        if (current != null && current.getId() == entityId) {
            return current;
        }
        return this.numen$player.level().getEntity(entityId);
    }

    @Unique
    private boolean numen$contains(Entity entity) {
        return this.numen$combatArea.contains(entity.getX(), entity.getY(), entity.getZ());
    }

    @Unique
    private void numen$markOutOfScope(int entityId) {
        if (this.numen$outOfScope.add(entityId)) {
            this.numen$record.unreachable(entityId);
        }
    }
}
