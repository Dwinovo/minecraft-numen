package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.ISttSettings;

/**
 * Forge implementation of {@link ISttSettings}: the voice-input entries live in the same TOML spec as the server
 * configuration ({@link ForgeNumenConfig#SPEC}), so saving one saves the file.
 */
public final class ForgeSttSettings implements ISttSettings {

    /** Default constructor used by {@code ServiceLoader}. */
    public ForgeSttSettings() {}

    @Override
    public String getSttProvider() {
        String s = ForgeNumenConfig.safe(ForgeNumenConfig.STT_PROVIDER);
        return s.isEmpty() ? "siliconflow" : s;
    }

    @Override
    public String getSttBaseUrl() {
        return ForgeNumenConfig.safe(ForgeNumenConfig.STT_BASE_URL);
    }

    @Override
    public String getSttModel() {
        return ForgeNumenConfig.safe(ForgeNumenConfig.STT_MODEL);
    }

    @Override
    public String getSttMicrophone() {
        return ForgeNumenConfig.safe(ForgeNumenConfig.STT_MICROPHONE);
    }

    @Override
    public void setSttProvider(String value) {
        ForgeNumenConfig.STT_PROVIDER.set(value == null || value.isBlank() ? "siliconflow" : value);
    }

    @Override
    public void setSttBaseUrl(String value) {
        ForgeNumenConfig.STT_BASE_URL.set(value == null ? "" : value);
    }

    @Override
    public void setSttModel(String value) {
        ForgeNumenConfig.STT_MODEL.set(value == null ? "" : value);
    }

    @Override
    public void setSttMicrophone(String value) {
        ForgeNumenConfig.STT_MICROPHONE.set(value == null ? "" : value);
    }

    /** 同服务端配置共用一份 TOML:写它就是写这个文件。 */
    @Override
    public void save() {
        if (ForgeNumenConfig.SPEC.isLoaded()) {
            ForgeNumenConfig.SPEC.save();
        }
        com.dwinovo.numen.api.Constants.LOG.info("[numen-config] saved");
    }
}
