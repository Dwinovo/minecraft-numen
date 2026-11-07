/**
 * 流式 TTS 语音管线（客户端）。模型一边生成回复,一边分句、逐句合成、
 * 按序从同伴身体位置以空间音源播放。
 *
 * <h2>数据流</h2>
 * <pre>
 * 循环内核的 ModelDelta 事件(正文增量,主线程)
 *   → {@link com.dwinovo.numen.api.client.voice.VoicePipeline#deltaSink}   （按轮次代际过滤）
 *   → {@link com.dwinovo.numen.api.client.voice.SentenceDivider}           （增量分句:首段逗号级,后续句末级）
 *   → {@link com.dwinovo.numen.api.client.voice.VoiceTextSanitizer}        （剥 markdown/动作描写/URL/标签）
 *   → {@link com.dwinovo.numen.api.client.voice.TtsBackend}                （并发预取合成,WAV 字节）
 *   → {@link com.dwinovo.numen.api.client.voice.WavCodec}                  （归一化为 16-bit mono PCM）
 *   → {@link com.dwinovo.numen.api.client.voice.EntityVoiceSound}          （SoundEngine 空间音源,跟随实体）
 * </pre>
 *
 * <h2>开关与配置</h2>
 * 一切由声线库驱动:声线是服务端的一种库（{@link com.dwinovo.numen.api.voice.VoiceProfile}
 * 有完整样例,不含 key）,她用哪条记在她的绑定里;合成用的 key 在这台电脑的钥匙串里,全局开关在
 * {@link com.dwinovo.numen.api.client.data.ClientPrefs}。{@link com.dwinovo.numen.api.client.voice.SpokenVoice}
 * 把三处合成一条能出声的声线,设置面板"语音"tab 是库的编辑界面。未绑定的同伴静音且零开销。
 *
 * <h2>生命周期</h2>
 * 管线归 {@code TurnPresenter} 所有:服务端大脑每次调模型推来 {@code TurnStart} 就 beginTurn,主人打断 /
 * 同伴死亡 interrupt。
 */
package com.dwinovo.numen.api.client.voice;
