package com.dwinovo.numen.nav;

import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.pathing.drive.EditLedger;

import net.minecraft.core.BlockPos;

/** 一段鞘翅滑翔的账:{@link Walked#glided} 里的一项。照实记身体做的:放了几支烟花、鞘翅耐久前后、落地掉了几点血、是不是迫降、收场时在不在空中。 */
@Doc("One elytra glide: where it took off and came down, what it used and what it cost.")
public record Gliding(@Doc("The cell it took off from.") BlockPos tookOffAt,
                      @Doc("The cell it came down in.") BlockPos landedAt,
                      @Doc("Horizontal blocks flown.") double blocks,
                      @Doc("Firework rockets it used.") int rockets,
                      @Doc("The elytra's damage before the glide.") int elytraDamageBefore,
                      @Doc("The elytra's damage after the glide.") int elytraDamageAfter,
                      @Doc("Health it lost to the landing.") double healthLost,
                      @Doc("It had to land early on the nearest ground it could reach (out of rockets, or blocked ahead).") boolean emergency,
                      @Doc("The glide ended with it still in the air.") boolean stillAirborne) {

    static Gliding of(EditLedger.Glided glide) {
        return new Gliding(glide.takeoff(), glide.landing(), glide.blocks(), glide.rockets(), glide.wearBefore(),
                glide.wearAfter(), glide.damage(), glide.emergency(), glide.airborne());
    }
}
