package com.dwinovo.numen.client.voice;

import java.util.concurrent.CompletableFuture;

/**
 * TTS 后端抽象：一句文本进，一段 WAV 字节出。实现必须异步（不阻塞调用线程），
 * 返回的字节由 {@link WavCodec#decode} 统一解码。
 *
 * <p>实现：
 * <ul>
 *   <li>{@link OpenAiCompatibleTts} — OpenAI {@code /v1/audio/speech} 协议,
 *       覆盖 OpenAI / 硅基流动等一票兼容服务；</li>
 *   <li>{@link GptSovitsTts} — GPT-SoVITS api_v2 风格的本地推理服务。</li>
 * </ul>
 */
public interface TtsBackend {

    /**
     * 合成一句话。失败以异常完成 future（管线记日志并跳过该句,不打断后续）。
     *
     * @param text 已清洗的句段（非空、非空白）
     * @return WAV 字节
     */
    CompletableFuture<byte[]> synthesize(String text);

    /**
     * 带情绪词的合成。{@code emotion} 是 LLM 句首 {@code [词]} 标签里的原词
     * (小写英文,可能是任何词,也可能为 null)。翻译成自家控制方式——换括号
     * 格式透传、映射到参数字段、不认识就忽略——由各实现自便;默认忽略,
     * 不支持情绪控制的后端零成本兼容。
     */
    default CompletableFuture<byte[]> synthesize(String text, String emotion) {
        return synthesize(text);
    }

    /** 面向日志的一句话描述（不含 apiKey）。 */
    String describe();
}
