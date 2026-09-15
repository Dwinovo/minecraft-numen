package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.client.chat.ChatDisplayModes;
import com.dwinovo.numen.client.chat.ChatLines;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.client.voice.VoicePipeline;
import com.dwinovo.numen.platform.Services;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一个 agent loop 的<b>表现层</b>:聊天框打字机、头顶气泡、说话状态上报、
 * 流式语音(TTS)的接线与在飞文本缓冲。只管"她看/听起来在说话",不碰
 * 会话状态与循环——那是循环内核的事,这里由门面按内核的事件驱动;删掉这一层,
 * 对话照常进行,只是又聋又哑。
 */
final class TurnPresenter {

    /**
     * 一次调模型的语音接线:正文增量的去处 + 收尾动作打包。语音未配置时是
     * {@link #SILENT_VOICE}(两样都是空操作)。
     */
    private record VoiceTurn(Consumer<String> sink, Runnable finish) {}

    private static final VoiceTurn SILENT_VOICE = new VoiceTurn(delta -> {}, () -> {});

    private final UUID entityUuid;
    /** 在飞回复流式中(打字机与说话位的数据源之一)。 */
    private final BooleanSupplier streamingActive;
    /** 大脑在输出(思考/生成/跑工具)——说话位取它与语音播报的并集。 */
    private final BooleanSupplier turnBusy;
    /** 人设名(可空);说话人显示名的第一优先。 */
    private final Supplier<String> personaName;

    /**
     * 本同伴的流式语音管线,懒创建:首次在声线库里 resolve 到这个 UUID 的
     * 绑定时才 new。未绑定 = 永远 null = 零开销。
     */
    private VoicePipeline voice;
    /** 这一次调模型的语音接线;{@link #beginTurn} 换上,{@link #endTurn} 收尾。 */
    private VoiceTurn voiceTurn = SILENT_VOICE;

    /** 在飞回复的已到 content 增量(流式打字机的数据源)。主线程读写:增量随内核的事件到达,
     *  只来自当前这次调用;回复落库/失败/切断时清空——committed 消息接管显示,永不双份。 */
    private final StringBuilder livePartial = new StringBuilder();
    /** 在飞回合的思考流(推理模型的 reasoning 增量)。与 {@link #livePartial} 同一套
     *  生命周期,落库后由 AssistantTurn 里那份 reasoning 接管显示,永不双份。 */
    private final StringBuilder liveReasoning = new StringBuilder();
    /** 上次刷进聊天框流式行的文本(变了才重刷,不逐 tick 折腾聊天框)。 */
    private String lastStreamedPartial = "";
    /** 上次发给服务端的说话状态(翻转才发包,不逐 tick 刷)。 */
    private boolean lastSpeakingSent;

    TurnPresenter(UUID entityUuid, BooleanSupplier streamingActive, BooleanSupplier turnBusy,
                  Supplier<String> personaName) {
        this.entityUuid = entityUuid;
        this.streamingActive = streamingActive;
        this.turnBusy = turnBusy;
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

    /** 半截打字作废:正文与思考流一起清。 */
    private void clearPartial() {
        livePartial.setLength(0);
        liveReasoning.setLength(0);
    }

    /** 每 client tick:语音管线推进、说话状态上报、聊天框打字机。 */
    void tick() {
        if (voice != null) voice.tick();
        syncSpeakingState();
        streamToChat();
    }

    /**
     * 要调一次模型了:清掉上一次的半截文字,开一轮语音(若该同伴绑定了声线)。每次都
     * 重新 resolve——声线库/绑定的编辑下一次生效;开新轮会打断上一轮还在播的残句。
     *
     * @param ownerBargeIn 主人的话还没被回应(硬停上一轮);否则句界衔接
     */
    void beginTurn(boolean ownerBargeIn) {
        clearPartial();
        VoiceLibrary.Entry cfg = VoiceLibrary.instance().resolve(entityUuid);
        if (cfg == null) {
            if (voice != null) voice.interrupt();   // 总开关关闭/解绑:静音存量队列
            voiceTurn = SILENT_VOICE;
            return;
        }
        if (voice == null) {
            voice = new VoicePipeline(entityUuid);
        }
        final var vp = voice;
        final int vgen = vp.beginTurn(cfg, ownerBargeIn);
        voiceTurn = new VoiceTurn(vp.deltaSink(vgen), () -> vp.endTurn(vgen));
    }

    /** 流式回复长出来一段:正文进打字机和语音,思考进思考流——主人能看见她在想什么,不只是省略号。 */
    void delta(String content, String reasoning) {
        if (!content.isEmpty()) {
            livePartial.append(content);
            voiceTurn.sink().accept(content);
        }
        liveReasoning.append(reasoning);
    }

    /** 这次调模型结束了(回复落库、失败或被切断):语音收尾,半截文字作废,聊天框摘掉在飞行。 */
    void endTurn() {
        voiceTurn.finish().run();
        voiceTurn = SILENT_VOICE;
        clearPartial();
        finishStreamLine();
    }

    /** 语音闭嘴:停播 + 清队列(打断/死亡)。 */
    void interruptVoice() {
        if (voice != null) voice.interrupt();
    }

    /**
     * 外接大脑的整段发声(say):按当前绑定现取声线,整段排到播放队尾——
     * 不开新轮、不清存量,连续的 say 自然连播。未绑声线 = 静默(气泡与聊天行照旧)。
     */
    void sayExternal(String text) {
        VoiceLibrary.Entry cfg = VoiceLibrary.instance().resolve(entityUuid);
        if (cfg == null) return;
        if (voice == null) voice = new VoicePipeline(entityUuid);
        voice.sayAppend(cfg, text);
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

    /** 流式行收尾:摘掉在飞行(定格行由回复落地时补)。 */
    private void finishStreamLine() {
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
