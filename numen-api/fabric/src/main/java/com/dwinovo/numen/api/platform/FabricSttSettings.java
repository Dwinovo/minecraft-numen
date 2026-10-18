package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.FabricNumenConfig.ConfigData;
import com.dwinovo.numen.api.platform.services.ISttSettings;

/**
 * Fabric implementation of {@link ISttSettings}: the voice-input fields live in the same {@code config/numen.json} as the
 * server configuration and share its in-memory data ({@link FabricNumenConfig#data()}).
 */
public final class FabricSttSettings implements ISttSettings {

    /** Default constructor used by {@code ServiceLoader}. */
    public FabricSttSettings() {}

    @Override
    public String getSttProvider() {
        ConfigData d = FabricNumenConfig.data();
        return d.sttProvider == null || d.sttProvider.isBlank() ? "siliconflow" : d.sttProvider;
    }

    @Override
    public String getSttBaseUrl() {
        String v = FabricNumenConfig.data().sttBaseUrl;
        return v == null ? "" : v;
    }

    @Override
    public String getSttModel() {
        String v = FabricNumenConfig.data().sttModel;
        return v == null ? "" : v;
    }

    @Override
    public String getSttMicrophone() {
        String v = FabricNumenConfig.data().sttMicrophone;
        return v == null ? "" : v;
    }

    @Override
    public void setSttProvider(String value) {
        FabricNumenConfig.data().sttProvider = value == null || value.isBlank() ? "siliconflow" : value;
    }

    @Override
    public void setSttBaseUrl(String value) {
        FabricNumenConfig.data().sttBaseUrl = value == null ? "" : value;
    }

    @Override
    public void setSttModel(String value) {
        FabricNumenConfig.data().sttModel = value == null ? "" : value;
    }

    @Override
    public void setSttMicrophone(String value) {
        FabricNumenConfig.data().sttMicrophone = value == null ? "" : value;
    }

    @Override
    public void save() {
        FabricNumenConfig.saveFile();
    }
}
