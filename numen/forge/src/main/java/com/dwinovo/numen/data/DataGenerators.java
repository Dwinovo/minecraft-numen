package com.dwinovo.numen.data;

import com.dwinovo.numen.Constants;
import net.minecraft.data.DataGenerator;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge data-generation entry point, run through {@code ./gradlew :numen:forge:Data}. GatherDataEvent is a mod-bus event. */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DataGenerators {

    private DataGenerators() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        generator.addProvider(event.includeServer(), new ModItemTagsProvider(generator.getPackOutput(),
                event.getLookupProvider(), event.getExistingFileHelper()));
    }
}
