package com.dwinovo.numen.client.voice;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 每同伴一条的流式语音管线：LLM 的 SSE content delta →
 * {@link SentenceDivider} 分句 → {@link VoiceTextSanitizer} 清洗 →
 * 段队列 → 并发预取合成（{@link TtsBackend}）→ <b>按序</b>从同伴身体位置播放
 * （{@link EntityVoiceSound}）。首句在首个逗号级边界就切出去送合成,
 * 开口延迟 ≈ LLM 首句时延 + 单句 TTS 时延。
 *
 * <h2>代际失效（generation）</h2>
 * 与 {@code EntityAgentLoop.turnGeneration} 同一思路：每次
 * {@link #beginTurn} / {@link #interrupt} 递增 {@link #generation}；
 * 分发时捕获的 gen 随着 chunk 回调、合成完成回调一路携带,落地时对不上号
 * 即整体丢弃。新 turn 开始、主人打断（Stop）、同伴死亡都会打断当前播放并
 * 清空队列;同伴不在世（客户端解析不到实体）时只清不播。
 *
 * <h2>线程模型</h2>
 * 所有可变状态只在客户端主线程上碰：
 * <ul>
 *   <li>chunk 回调发生在 HTTP executor 线程 → {@code Minecraft.execute}
 *       蹦回主线程（FIFO,顺序保持）再喂 divider;</li>
 *   <li>合成完成同样蹦回主线程落格;</li>
 *   <li>{@link #tick} / {@link #interrupt} 本来就在主线程。</li>
 * </ul>
 *
 * <h2>预取</h2>
 * 队首 {@value #PREFETCH} 段并发合成——下一段在上一段还在播时就开始请求,
 * 播完即接。更深的窗口对本地 GPT-SoVITS 只会造成排队,对按量计费的云服务
 * 则是白花钱（打断时丢弃）,2 是个经验平衡点。
 */
public final class VoicePipeline {

    /** 同时在途的合成请求上限（含正在播的下一段）。 */
    private static final int PREFETCH = 2;

    /** 一段文本的三态：待合成 → 音频就绪 / 失败。 */
    private static final class Segment {
        final String text;
        boolean synthStarted;
        PcmAudio audio;
        boolean failed;
        Segment(String text) { this.text = text; }
    }

    private final UUID entityUuid;
    private final SentenceDivider divider = new SentenceDivider();
    private final ArrayDeque<Segment> queue = new ArrayDeque<>();

    private TtsBackend backend;
    private float volume = 1.0f;
    private int generation;
    private EntityVoiceSound playing;

    public VoicePipeline(UUID entityUuid) {
        this.entityUuid = entityUuid;
    }

    /**
     * 一次 LLM 分发开始：打断上一轮残余（停播 + 清队列 + 重置分句器）,
     * 按当前绑定的声线重建后端,返回本轮的代际号。主线程调用。
     */
    public int beginTurn(VoiceLibrary.Entry cfg) {
        generation++;
        stopAndClear();
        this.backend = cfg.createBackend();
        this.volume = cfg.volume();
        return generation;
    }

    /**
     * 供 {@code chatStreaming(..., onChunk)} 用的 chunk 回调。从 provider 原始
     * chunk JSON 里只取 {@code choices[0].delta.content} 的文本增量——
     * {@code reasoning_content}、{@code tool_calls} 增量都不进语音。
     * 回调在 HTTP 线程触发,内部蹦回主线程。
     */
    public Consumer<JsonObject> chunkSink(int gen) {
        return chunk -> {
            String delta = extractContentDelta(chunk);
            if (delta == null || delta.isEmpty()) return;
            Minecraft.getInstance().execute(() -> {
                if (gen != generation) return;
                enqueue(divider.feed(delta));
            });
        };
    }

    /** 流结束（正常或出错都调）：flush 分句器余量。任意线程可调。 */
    public void endTurn(int gen) {
        Minecraft.getInstance().execute(() -> {
            if (gen != generation) return;
            enqueue(divider.flush());
        });
    }

    /** 停播 + 清队列 + 作废一切在途回调。主线程调用（abort / 死亡 / 收尾）。 */
    public void interrupt() {
        generation++;
        stopAndClear();
    }

    /** 每客户端 tick 驱动一次：推进播放接续。主线程调用。 */
    public void tick() {
        if (playing == null && queue.isEmpty()) return;
        pumpPlayback();
    }

    /** 有段在播或还有段没播完（调试/状态用）。 */
    public boolean isSpeaking() {
        return playing != null || !queue.isEmpty();
    }

    // ---- internals（全部主线程） ----

    private void enqueue(List<String> rawSegments) {
        for (String raw : rawSegments) {
            String clean = VoiceTextSanitizer.clean(raw);
            if (clean.isEmpty()) continue;   // 纯记号/纯标点段:跳过,不浪费请求
            queue.add(new Segment(clean));
        }
        pumpSynthesis();
        pumpPlayback();
    }

    /** 给队首至多 {@link #PREFETCH} 个未启动的段发起合成。 */
    private void pumpSynthesis() {
        if (backend == null) return;
        final int gen = generation;
        int inWindow = 0;
        for (Segment seg : queue) {
            if (inWindow >= PREFETCH) break;
            if (seg.failed) continue;
            inWindow++;
            if (seg.synthStarted) continue;
            seg.synthStarted = true;
            final Segment target = seg;
            final long t0 = System.nanoTime();
            backend.synthesize(seg.text).whenComplete((wav, err) -> {
                PcmAudio decoded = null;
                Throwable failure = err;
                if (err == null) {
                    try {
                        // 归一化+用户增益烙进采样(MC 实例音量被引擎钳在 1.0,只能在这做响度)。
                        decoded = WavCodec.decode(wav).amplified(volume);
                    } catch (Exception ex) {
                        failure = ex;
                    }
                }
                final PcmAudio audio = decoded;
                final Throwable fail = failure;
                Minecraft.getInstance().execute(() -> {
                    if (gen != generation) return;   // 本轮已被打断,结果作废
                    if (fail != null) {
                        target.failed = true;
                        Constants.LOG.warn("[numen-voice#{}] 合成失败({}), 跳过该句: {} — {}",
                                entityUuid, backend.describe(), truncate(target.text), unwrap(fail));
                    } else {
                        target.audio = audio;
                        // INFO 而非 debug:每句一行,是"流式分句确实在 LLM 说完前就开始
                        // 合成"的唯一运行时证据,也是合成延迟的常驻观测点。
                        Constants.LOG.info("[numen-voice#{}] 合成完成 {}ms, {}ms 音频: {}",
                                entityUuid, (System.nanoTime() - t0) / 1_000_000,
                                audio.durationMs(), truncate(target.text));
                    }
                    pumpSynthesis();
                    pumpPlayback();
                });
            });
        }
    }

    /** 上一段播完且队首音频就绪 → 从同伴当前位置接着播。 */
    private void pumpPlayback() {
        Minecraft mc = Minecraft.getInstance();
        if (playing != null) {
            if (mc.getSoundManager().isActive(playing)) return;   // 还在播
            playing = null;
        }
        while (!queue.isEmpty() && queue.peek().failed) {
            queue.poll();   // 失败段直接跳过,继续后面的
            pumpSynthesis();
        }
        Segment head = queue.peek();
        if (head == null || head.audio == null) return;   // 空了,或还在合成

        AbstractClientPlayer body = ClientNumenLookup.resolve(entityUuid);
        if (body == null) {
            // 同伴不在世:只清不播。
            Constants.LOG.info("[numen-voice#{}] 实体不在场,丢弃 {} 段待播语音",
                    entityUuid, queue.size());
            stopAndClear();
            return;
        }
        queue.poll();
        // 经平台工厂创建:NeoForge 返回覆写官方 getStream 补丁钩子的子类,
        // Fabric 返回原类走 vanilla mixin——取数机制两侧不同(见 IVoiceSoundFactory)。
        // 响度已烙进 PCM(amplified),实例音量恒 1.0——只留 3D 距离衰减。
        playing = com.dwinovo.numen.platform.Services.VOICE
                .entityVoice(entityUuid, body, head.audio, 1.0f);
        mc.getSoundManager().play(playing);
        Constants.LOG.info("[numen-voice#{}] 开播 {}ms: {}",
                entityUuid, head.audio.durationMs(), truncate(head.text));
        pumpSynthesis();
    }

    private void stopAndClear() {
        if (playing != null) {
            Minecraft.getInstance().getSoundManager().stop(playing);
            playing = null;
        }
        queue.clear();
        divider.reset();
    }

    /**
     * 从 OpenAI 协议的流式 chunk 里取出 content 文本增量,
     * 与 provider 侧 {@code accumulateChunk} 对 content 的解析保持一致：
     * {@code choices[0].delta.content} 且必须是字符串原语。
     * 没有 content（reasoning/tool_call/usage 帧）返回 null。
     */
    public static String extractContentDelta(JsonObject chunk) {
        if (chunk == null || !chunk.has("choices")) return null;
        JsonElement choicesEl = chunk.get("choices");
        if (!choicesEl.isJsonArray()) return null;
        JsonArray choices = choicesEl.getAsJsonArray();
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) return null;
        JsonObject choice = choices.get(0).getAsJsonObject();
        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) return null;
        JsonObject delta = choice.getAsJsonObject("delta");
        if (!delta.has("content") || !delta.get("content").isJsonPrimitive()
                || !delta.get("content").getAsJsonPrimitive().isString()) return null;
        return delta.get("content").getAsString();
    }

    private static String truncate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    private static String unwrap(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur != cur.getCause()) cur = cur.getCause();
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
