package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenPaths;
import com.dwinovo.numen.api.platform.services.INumenConfig;
import com.dwinovo.numen.api.store.Atomic;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Paper 上的 {@link INumenConfig}:配置根下的 {@code config.json},第一次用到时没有就写一份带出厂值的。
 * 服务端只用代理与系统提示词两项;语音识别是客户端的设置,服务端没有设置面板,也就没有写入口。
 * 文件改了要重启才生效。
 *
 * <p>文件坏了(不是合法的 JSON)是启动期的错,直接抛出并指明是哪个文件,不动原文件,也不悄悄换成出厂值顶上。
 */
public final class PaperNumenConfig implements INumenConfig {

    private static final String FILE_NAME = "config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigData data;

    @Override
    public synchronized String getProxy() {
        return data().proxy;
    }

    @Override
    public synchronized String getSystemPrompt() {
        return data().systemPrompt;
    }

    private ConfigData data() {
        if (data == null) {
            data = load(NumenPaths.config().resolve(FILE_NAME));
        }
        return data;
    }

    private static ConfigData load(Path file) {
        if (!Files.exists(file)) {
            ConfigData defaults = new ConfigData();
            Atomic.write(file, GSON.toJson(defaults));
            Constants.LOG.info("[numen-config] wrote default config to {}", file);
            return defaults;
        }
        try (Reader in = Files.newBufferedReader(file)) {
            ConfigData parsed = GSON.fromJson(in, ConfigData.class);
            if (parsed == null) {
                throw new IllegalStateException(file + " 是空的:填上内容,或删掉它让 Numen 重新写一份");
            }
            return parsed.withDefaults();
        } catch (JsonParseException e) {
            throw new IllegalStateException(file + " 不是合法的 JSON:" + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("读不出 " + file, e);
        }
    }

    // ---- 写入口:Paper 服务端没有设置面板 ----

    @Override
    public void setProxy(String value) {
        throw readOnly();
    }

    @Override
    public void setSystemPrompt(String value) {
        throw readOnly();
    }

    @Override
    public void save() {
        throw readOnly();
    }

    // ---- 语音识别:客户端的设置 ----

    @Override
    public String getSttProvider() {
        throw clientOnly();
    }

    @Override
    public String getSttBaseUrl() {
        throw clientOnly();
    }

    @Override
    public String getSttModel() {
        throw clientOnly();
    }

    @Override
    public String getSttMicrophone() {
        throw clientOnly();
    }

    @Override
    public void setSttProvider(String value) {
        throw clientOnly();
    }

    @Override
    public void setSttBaseUrl(String value) {
        throw clientOnly();
    }

    @Override
    public void setSttModel(String value) {
        throw clientOnly();
    }

    @Override
    public void setSttMicrophone(String value) {
        throw clientOnly();
    }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("Paper 服务端的 Numen 配置只读:改 " + FILE_NAME + " 后重启");
    }

    private static UnsupportedOperationException clientOnly() {
        return new UnsupportedOperationException("语音识别是客户端的设置,服务端没有");
    }

    /** 文件的内容。字段名就是 JSON 的键。 */
    private static final class ConfigData {
        /** 给人看的说明,读取时忽略。 */
        public String _readme = "Numen server configuration. Field names are camelCase. Restart for changes to take effect.";
        /** 调模型用的 HTTP 代理,{@code host:port};空是直连。 */
        public String proxy = "";
        public String systemPrompt = DEFAULT_SYSTEM_PROMPT;

        /** 文件里缺的项用出厂值补上。 */
        ConfigData withDefaults() {
            if (proxy == null) proxy = "";
            if (systemPrompt == null) systemPrompt = DEFAULT_SYSTEM_PROMPT;
            return this;
        }
    }
}
