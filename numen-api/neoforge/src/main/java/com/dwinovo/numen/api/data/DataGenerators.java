package com.dwinovo.numen.api.data;

import com.dwinovo.numen.api.Constants;
import net.minecraft.data.PackOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * NeoForge data-generation entry point. Auto-registered via
 * {@link EventBusSubscriber}; runs through
 * {@code ./gradlew :neoforge:runData}. Outputs land in
 * {@code neoforge/src/generated/resources/}, already wired into the main
 * resource source set by the subproject's {@code build.gradle}.
 */
// GatherDataEvent comes in Client/Server variants and the clientData run (see build.gradle) only fires the Client
// one: listening to the base class compiles but is never called. FML 7 puts each listener on the bus its event
// type belongs to (naming a bus is deprecated), so no bus is named here.
@EventBusSubscriber(modid = Constants.MOD_ID)
public final class DataGenerators {

    private DataGenerators() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent.Client event) {
        PackOutput output = event.getGenerator().getPackOutput();
        event.getGenerator().addProvider(true, new ModLanguageProvider(output, "en_us"));
        event.getGenerator().addProvider(true, new ModLanguageProvider(output, "zh_cn"));
    }
}
