package com.dwinovo.numen.data;

import com.dwinovo.numen.Constants;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge data-generation entry point. Auto-registered via
 * {@link Mod.EventBusSubscriber} on the MOD bus; runs through
 * {@code ./gradlew :forge:runData}. Outputs land in
 * {@code forge/src/generated/resources/}, already wired into the main resource
 * source set by the subproject's {@code build.gradle}.
 *
 * <p>Mirrors the NeoForge reference: only the shared {@link ModLanguageData}
 * translation catalogue is generated here.
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DataGenerators {

    private DataGenerators() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();   // 1.20.4: PackOutput is on the generator, not the event

        generator.addProvider(event.includeClient(), new ModLanguageProvider(output, "en_us"));
        generator.addProvider(event.includeClient(), new ModLanguageProvider(output, "zh_cn"));
    }
}
