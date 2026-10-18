package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.ISttSettings;

/**
 * NeoForge implementation of {@link ISttSettings}: the voice-input entries live in the same TOML spec as the server
 * configuration ({@link NeoForgeNumenConfig#SPEC}), so saving one saves the file.
 */
public final class NeoForgeSttSettings implements ISttSettings {

    /** Default constructor used by {@code ServiceLoader}. */
    public NeoForgeSttSettings() {}

    @Override
    public String getSttProvider() {
        String s = NeoForgeNumenConfig.safe(NeoForgeNumenConfig.STT_PROVIDER);
        return s.isEmpty() ? "siliconflow" : s;
    }

    @Override
    public String getSttBaseUrl() {
        return NeoForgeNumenConfig.safe(NeoForgeNumenConfig.STT_BASE_URL);
    }

    @Override
    public String getSttModel() {
        return NeoForgeNumenConfig.safe(NeoForgeNumenConfig.STT_MODEL);
    }

    @Override
    public String getSttMicrophone() {
        return NeoForgeNumenConfig.safe(NeoForgeNumenConfig.STT_MICROPHONE);
    }

    @Override
    public void setSttProvider(String value) {
        NeoForgeNumenConfig.STT_PROVIDER.set(value == null || value.isBlank() ? "siliconflow" : value);
    }

    @Override
    public void setSttBaseUrl(String value) {
        NeoForgeNumenConfig.STT_BASE_URL.set(value == null ? "" : value);
    }

    @Override
    public void setSttModel(String value) {
        NeoForgeNumenConfig.STT_MODEL.set(value == null ? "" : value);
    }

    @Override
    public void setSttMicrophone(String value) {
        NeoForgeNumenConfig.STT_MICROPHONE.set(value == null ? "" : value);
    }

    /** 同服务端配置共用一份 TOML:写它就是写这个文件。 */
    @Override
    public void save() {
        if (NeoForgeNumenConfig.SPEC.isLoaded()) {
            NeoForgeNumenConfig.SPEC.save();
        }
        com.dwinovo.numen.api.Constants.LOG.info("[numen-config] saved");
    }
}
