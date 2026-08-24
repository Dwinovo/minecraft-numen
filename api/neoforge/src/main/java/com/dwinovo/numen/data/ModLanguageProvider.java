package com.dwinovo.numen.data;

import com.dwinovo.numen.Constants;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

/**
 * NeoForge-side translation provider. Mirrors {@code FabricModLanguageProvider}
 * — both delegate to the shared {@link ModLanguageData} catalogue.
 */
public final class ModLanguageProvider extends LanguageProvider {

    private final String locale;

    public ModLanguageProvider(PackOutput output, String locale) {
        super(output, Constants.MOD_ID, locale);
        this.locale = locale;
    }

    @Override
    protected void addTranslations() {
        ModLanguageData.addTranslations(locale, this::add);
    }
}
