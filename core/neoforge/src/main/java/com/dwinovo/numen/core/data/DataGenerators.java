package com.dwinovo.numen.core.data;

import com.dwinovo.numen.core.Constants;
import net.minecraft.data.PackOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * NeoForge data-generation entry point for numen-core. Auto-registered via
 * {@link EventBusSubscriber}; runs through {@code ./gradlew :neoforge:runData}.
 * Emits core's tags (the engine generates its own GUI language separately).
 * Outputs land in {@code neoforge/src/generated/resources/}, already wired into
 * the main resource source set by the subproject's {@code build.gradle}.
 */
// 1.21.1 has separate buses; GatherDataEvent is a mod-bus event and predates the
// 1.21.4 Client/Server split, and NeoForge tag providers still want an ExistingFileHelper.
@EventBusSubscriber(modid = Constants.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class DataGenerators {

    private DataGenerators() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        PackOutput output = event.getGenerator().getPackOutput();
        event.getGenerator().addProvider(true, new ModItemTagsProvider(output, event.getLookupProvider()));
        event.getGenerator().addProvider(true,
                new ModBlockTagsProvider(output, event.getLookupProvider(), event.getExistingFileHelper()));
    }
}
