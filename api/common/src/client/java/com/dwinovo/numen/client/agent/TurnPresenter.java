package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.client.chat.ChatDisplayModes;
import com.dwinovo.numen.client.chat.ChatLines;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.client.voice.VoicePipeline;
import com.dwinovo.numen.platform.Services;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 一个 agent loop 的<b>表现层</b>:聊天框打字机、头顶气泡、说话状态上报、
 * 流式语音(TTS)的接线与在飞文本缓冲。只管"她看/听起来在说话",不碰
 * 会话状态与回合机——那是 {@link EntityAgentLoop} 的事;删掉这一层,
 * 对话照常进行,只是又聋又哑。
 */
final class TurnPresenter {

    /**
     * 一次 LLM 分发的语音接线:chunk 回调 + 收尾动作打包。语音未配置时是
     * {@link #SILENT_VOICE}(sink 为 null、finish 是空操作),chatStreaming
     * 收到 null onChunk 即走无语音那条路。
     */
    record VoiceTurn(Consumer<JsonObject> sink, Runnable finish) {}

    private static final VoiceTurn SILENT_VOICE = new VoiceTurn(null, () -> {});

    private final UUID entityUuid;
    /** 在飞回复流式中(打字机与说话位的数据源之一)。 */
    private final BooleanSupplier streamingActive;
    /** 大脑在输出(思考/生成/跑工具)——说话位取它与语音播报的并集。 */
    private final BooleanSupplier turnBusy;
    /** 当前回合代际(乱序 chunk 丢弃判据)。 */
    private final IntSupplier generation;
    /** 人设名(可空);说话人显示名的第一优先。 */
    private final Supplier<String> personaName;

    /**
     * 本同伴的流式语音管线,懒创建:首次在声线库里 resolve 到这个 UUID 的
     * 绑定时才 new。未绑定 = 永远 null = 零开销。
     */
    private VoicePipeline voice;

    /** 在飞回复的已到 content 增量(流式打字机的数据源)。主线程读写:chunk 在
     *  HTTP 线程到达后经 {@code Minecraft.execute} 蹦回来追加,代际不符直接丢;
     *  回复落库/打断/死亡时清空——committed 消息接管显示,永不双份。 */
    private final StringBuilder livePartial = new StringBuilder();
    /** 在飞回合的思考流(推理模型的 reasoning 增量)。与 {@link #livePartial} 同一套
     *  生命周期:同代际到达才追加,落库/打断/死亡一起清——落库后由 AssistantTurn
     *  里那份 reasoning 接管显示,永不双份。 */
    private final StringBuilder liveReasoning = new StringBuilder();
    /** 上次刷进聊天框流式行的文本(变了才重刷,不逐 tick 折腾聊天框)。 */
    private String lastStreamedPartial = "";
    /** 上次发给服务端的说话状态(翻转才发包,不逐 tick 刷)。 */
    private boolean lastSpeakingSent;

    TurnPresenter(UUID entityUuid, BooleanSupplier streamingActive, BooleanSupplier turnBusy,
                  IntSupplier generation, Supplier<String> personaName) {
        this.entityUuid = entityUuid;
        this.streamingActive = streamingActive;
        this.turnBusy = turnBusy;
        this.generation = generation;
        this.personaName = personaName;
    }

    /** Live partial of the in-flight assistant reply ("" when idle) — GUI typewriter source. */
    String livePartial() {
        return livePartial.toString();
    }

    /** 在飞回合的思考流("" = 没有或已落库)——G 面板思考块的流式数据源。 */
    String liveReasoning() {
        return liveReasoning.toString();
    }

    /** 半截打字作废(打断/死亡/回复落库时):正文与思考流一起清。 */
    void clearPartial() {
        livePartial.setLength(0);
        liveReasoning.setLength(0);
    }

    /** 每 client tick:语音管线推进、说话状态上报、聊天框打字机。 */
    void tick() {
        if (voice != null) voice.tick();
        syncSpeakingState();
        streamToChat();
    }

    /** onChunk 接线:content delta 直通 UI 的 live-partial,原始 chunk 原样继续喂语音。 */
    Consumer<JsonObject> tapForUi(int gen, Consumer<JsonObject> voiceSink) {
        return tapForUi(gen, voiceSink, null);
    }

    /**
     * 带思考流分接头的版本:reasoningDelta(provider 的方言解码)抽出的思考
     * 增量喂头顶思考泡的本地流——主人能看见她在想什么,不只是省略号。
     */
    Consumer<JsonObject> tapForUi(int gen, Consumer<JsonObject> voiceSink,
                                  java.util.function.Function<JsonObject, String> reasoningDelta) {
        return chunk -> {
            String delta = VoicePipeline.extractContentDelta(chunk);
            if (delta != null && !delta.isEmpty()) {
                Minecraft.getInstance().execute(() -> {
                    if (gen == generation.getAsInt()) livePartial.append(delta);
                });
            }
            if (reasoningDelta != null) {
                String r = reasoningDelta.apply(chunk);
                if (r != null && !r.isEmpty()) {
                    Minecraft.getInstance().execute(() -> {
                        if (gen == generation.getAsInt()) liveReasoning.append(r);
                    });
                }
            }
            if (voiceSink != null) voiceSink.accept(chunk);
        };
    }

    /**
     * 为即将发出的 chat 请求开启一轮语音(若该同伴绑定了声线)。每次分发都
     * 重新 resolve——声线库/绑定的编辑下一轮生效;开新轮会打断上一轮还在
     * 播的残句(新内容优先,与打断语义一致)。
     */
    /** {@code ownerBargeIn} = 本轮由主人夺话触发(硬停上一轮);否则句界衔接。 */
    VoiceTurn beginVoiceTurn(boolean ownerBargeIn) {
        VoiceLibrary.Entry cfg = VoiceLibrary.instance().resolve(entityUuid);
        if (cfg == null) {
            if (voice != null) voice.interrupt();   // 总开关关闭/解绑:静音存量队列
            return SILENT_VOICE;
        }
        if (voice == null) {
            voice = new VoicePipeline(entityUuid);
        }
        final var vp = voice;
        final int vgen = vp.beginTurn(cfg, ownerBargeIn);
        return new VoiceTurn(vp.chunkSink(vgen), () -> vp.endTurn(vgen));
    }

    /** 语音闭嘴:停播 + 清队列(打断/死亡)。 */
    void interruptVoice() {
        if (voice != null) voice.interrupt();
    }

    /** 聊天框的打字机:在飞回复逐 tick 长出来——不开面板也能实时看她说话。 */
    private void streamToChat() {
        if (!streamingActive.getAsBoolean() || livePartial.length() == 0) {
            return;
        }
        String filtered = ChatDisplayModes.current()
                .assistantText(livePartial.toString());
        if (filtered.isBlank() || filtered.equals(lastStreamedPartial)) {
            return;
        }
        lastStreamedPartial = filtered;
        ChatLines.streaming(entityUuid, speakerName(), filtered);
    }

    /** 流式行收尾:摘掉在飞行(定格行由各分支自己补)。 */
    void finishStreamLine() {
        if (!lastStreamedPartial.isEmpty()) {
            lastStreamedPartial = "";
            ChatLines.streamingDone(entityUuid);
        }
    }

    /** 说话人显示名:人设名优先,否则花名册名。 */
    String speakerName() {
        String persona = personaName.get();
        return persona != null && !persona.isBlank()
                ? persona
                : String.valueOf(NumenRoster.instance().name(entityUuid));
    }

    /** 大脑在输出(思考/生成/跑工具/语音在播)→ 告诉身体,好在说话期间注视主人。 */
    private void syncSpeakingState() {
        // 退出游戏的最后几个 client tick 里连接已拆——此时发包会在
        // PacketDistributor.sendToServer 里 NPE 崩掉客户端。断线期不发,
        // 状态留在 lastSpeakingSent 里,重连后首次翻转自然补上。
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        boolean speaking = turnBusy.getAsBoolean() || (voice != null && voice.isSpeaking());
        if (speaking != lastSpeakingSent) {
            lastSpeakingSent = speaking;
            Services.NETWORK.sendToServer(new com.dwinovo.numen.network.payload.SpeakingStatePayload(
                    entityUuid, speaking));
        }
    }

}
