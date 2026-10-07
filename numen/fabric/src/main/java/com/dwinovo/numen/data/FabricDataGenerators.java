package com.dwinovo.numen.data;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * Fabric data-generation entry point for Numen. Wired via the
 * {@code fabric-datagen} entrypoint in {@code fabric.mod.json}; runs through
 * {@code ./gradlew :fabric:runDatagen}. Emits Numen's tags (Numen API generates
 * its own GUI language separately). Outputs land in
 * {@code fabric/src/generated/resources/}, added back to the main resource
 * source set so the JSONs ship inside the jar.
 */
public final class FabricDataGenerators implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        FabricDataGenerator.Pack pack = generator.createPack();
        pack.addProvider(FabricModItemTagsProvider::new);
        pack.addProvider(FabricModBlockTagsProvider::new);
    }
}
