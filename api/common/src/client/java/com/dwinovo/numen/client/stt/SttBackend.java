package com.dwinovo.numen.client.stt;

/**
 * STT(语音转文字)后端抽象:开一个会话,喂 PCM,出文本。一个统一接口同时罩住
 * 批量(录完整段上传)与流式(边说边传 WebSocket)——采集层与接线层不关心底下
 * 是哪种范式,接入新提供商只需实现本接口 + 在 {@link SttProviders} 注册。
 *
 * <p>实现:
 * <ul>
 *   <li>{@link WhisperHttpStt} — OpenAI 兼容 {@code /v1/audio/transcriptions}
 *       批量上传,覆盖 OpenAI / 硅基流动 / Groq 等一票 Whisper 兼容服务;</li>
 *   <li>(未来)流式 WebSocket 后端(阿里/腾讯实时 ASR)照接口塞入,上层不动。</li>
 * </ul>
 */
public interface SttBackend {

    /** 开一次转写会话,结果经 {@code listener} 回调。 */
    SttSession open(SttListener listener);

    /** 面向日志的一句话描述(不含 apiKey)。 */
    String describe();
}
