package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.NumenMod;
import com.dwinovo.numen.fix.NumenRuntimeFixes;
import com.dwinovo.numen.core.combat.CombatDeathEvents;
import com.dwinovo.numen.core.scaffold.TemporaryScaffoldEvents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NumenMod.class)
public abstract class OwnerLogoutMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void numen$registerOwnerLogoutCleanup(
        IEventBus modEventBus,
        ModContainer container,
        CallbackInfo callback
    ) {
        NeoForge.EVENT_BUS.addListener(NumenRuntimeFixes::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(
            EventPriority.LOWEST,
            false,
            LivingDeathEvent.class,
            CombatDeathEvents::onLivingDeath
        );
        NeoForge.EVENT_BUS.addListener(TemporaryScaffoldEvents::onEntityPlace);
    }
}
