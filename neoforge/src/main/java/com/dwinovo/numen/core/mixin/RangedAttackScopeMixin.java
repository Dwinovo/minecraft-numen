package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.combat.CombatArea;
import com.dwinovo.numen.core.combat.CombatAreaRegistry;
import com.dwinovo.numen.core.combat.CombatAreaReport;
import com.dwinovo.numen.core.task.RangedAttackTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.task.RangedAttackCompanionTask")
public abstract class RangedAttackScopeMixin {
    @Shadow private Entity target;

    @Unique private CombatArea numen$combatArea;
    @Unique private NumenPlayer numen$player;
    @Unique private RangedAttackTaskRecord numen$record;
    @Unique private Set<Integer> numen$outOfScope;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void numen$captureCombatArea(
        NumenPlayer player,
        RangedAttackTaskRecord record,
        CallbackInfo callback
    ) {
        this.numen$player = player;
        this.numen$record = record;
        this.numen$outOfScope = new TreeSet<>();
        this.numen$combatArea = CombatAreaRegistry.resolve(player, record.entityIds);
    }

    @Inject(method = "selectTarget", at = @At("HEAD"))
    private void numen$excludeTargetsOutsideArea(CallbackInfoReturnable<Entity> callback) {
        for (int entityId : this.numen$record.entityIds) {
            if (this.numen$record.terminal(entityId)) {
                continue;
            }
            Entity entity = this.numen$targetEntity(entityId);
            if (entity != null && !entity.isRemoved() && !isDead(entity) && !this.numen$contains(entity)) {
                this.numen$markOutOfScope(entityId);
            }
        }
    }

    @Inject(method = "validateCurrentTarget", at = @At("HEAD"), cancellable = true)
    private void numen$stopChasingOutsideArea(CallbackInfo callback) {
        if (this.target == null
            || this.target.isRemoved()
            || isDead(this.target)
            || this.numen$contains(this.target)) {
            return;
        }
        this.numen$markOutOfScope(this.target.getId());
        ((RangedAttackTaskAccessor) this).numen$clearTarget();
        callback.cancel();
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
        Entity current = this.target;
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

    @Unique
    private static boolean isDead(Entity entity) {
        return entity instanceof LivingEntity living && living.isDeadOrDying();
    }
}
