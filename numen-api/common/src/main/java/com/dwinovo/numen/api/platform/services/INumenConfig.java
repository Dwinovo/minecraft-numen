package com.dwinovo.numen.api.platform.services;

/**
 * Cross-loader surface for the user-editable server configuration. Each loader
 * ships its own implementation hooked into its native config system —
 * NeoForge wraps a {@code ModConfigSpec} (TOML), Fabric reads a Gson JSON
 * file under {@code <gameDir>/config/numen.json}.
 *
 * <h2>Read freshness</h2>
 * Values are read on every call. NeoForge's {@code ConfigValue.get()} is
 * already lazy + cached at the framework level; Fabric's JSON impl caches
 * the parsed values in memory. Editing the config file therefore takes effect
 * on the next server restart, not immediately.
 *
 * <p>只有服务端用的项(代理)。语音识别的设置是这台电脑(客户端)的,在 {@code ISttSettings}。
 */
public interface INumenConfig {

    /** Optional HTTP proxy for LLM calls as {@code host:port} (empty = direct). For users behind a firewall. */
    String getProxy();

    // ---- 写入口:管理员在客户端的设置面板里改服务器的这一项(经 Libraries 的 ServerConfig) ----

    void setProxy(String value);

    /**
     * Flush in-memory changes to the loader-native config file. Best-effort:
     * errors are logged but never thrown — settings GUI shouldn't crash the
     * client because of a write failure.
     *
     * <p>Fabric impl rewrites {@code config/numen.json} directly. NeoForge
     * impl writes through {@code ModConfigSpec.ConfigValue.set()}, which
     * NeoForge persists to {@code numen-common.toml} on its own schedule
     * (this method nudges that schedule when possible).
     */
    void save();
}
