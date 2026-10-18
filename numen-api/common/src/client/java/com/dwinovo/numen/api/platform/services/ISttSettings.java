package com.dwinovo.numen.api.platform.services;

/**
 * 语音输入(STT)的设置——这台电脑的,全局一个麦克风、一个识别服务。服务端没有它,所以和服务端配置({@link INumenConfig})分开,
 * 只在客户端源码集里,经 {@code ClientServices.STT} 取。各加载器照自己的配置系统实现(与服务端配置存在同一份文件里)。
 *
 * <p>识别服务的 key 不在这里:它在这台电脑的钥匙串里(config/numen/client/keys/),按识别服务商存。
 */
public interface ISttSettings {

    /** STT provider id selecting the preset (wire adapter + default endpoint). Default {@code siliconflow}. */
    String getSttProvider();

    /** STT base URL override. Empty = use the provider preset's default endpoint. */
    String getSttBaseUrl();

    /** STT model id (e.g. {@code whisper-1}, {@code FunAudioLLM/SenseVoiceSmall}). */
    String getSttModel();

    /** Chosen input device name; empty = first available microphone. */
    String getSttMicrophone();

    void setSttProvider(String value);

    void setSttBaseUrl(String value);

    void setSttModel(String value);

    void setSttMicrophone(String value);

    /** 把改动落到配置文件。尽力而为:写失败只记日志,设置面板不该因此崩。 */
    void save();
}
