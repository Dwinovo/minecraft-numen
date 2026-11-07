package com.dwinovo.numen.data;

import com.dwinovo.numen.Constants;
import net.minecraft.data.PackOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * NeoForge data-generation entry point for Numen. Auto-registered via
 * {@link EventBusSubscriber}; runs through {@code ./gradlew :neoforge:runData}.
 * Emits Numen's tags (Numen API generates its own GUI language separately).
 * Outputs land in {@code neoforge/src/generated/resources/}, already wired into
 * the main resource source set by the subproject's {@code build.gradle}.
 */
// 1.21.4 still has separate buses (1.21.5 merged them); GatherDataEvent is a mod-bus event, and FML 6 does not
// pick the bus from the event type, so the mod bus is named here. 1.21.4 split it into Client/Server
// variants and the clientData run (see build.gradle) only fires the Client one: listening to the base class
// compiles but is never called.
@EventBusSubscriber(modid = Constants.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class DataGenerators {

    private DataGenerators() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent.Client event) {
        PackOutput output = event.getGenerator().getPackOutput();
        event.getGenerator().addProvider(true, new ModItemTagsProvider(output, event.getLookupProvider()));
    }
}
