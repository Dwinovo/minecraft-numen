package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.loop.Hold;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.LoopStatus;
import com.dwinovo.numen.agent.loop.Phase;
import com.dwinovo.numen.agent.loop.RunEnd;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.client.chat.ChatDisplayModes;
import com.dwinovo.numen.client.chat.ChatLines;
import com.dwinovo.numen.client.hud.NumenHudToasts;
import com.dwinovo.numen.client.hud.SpeechBubbles;
import com.dwinovo.numen.client.ui.NumenToasts;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.client.voice.VoicePipeline;
import com.dwinovo.numen.mcp.server.McpTranscript;
import com.dwinovo.numen.platform.Services;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一只同伴的<b>表现层</b>:聊天框打字机、头顶气泡、聊天栏的话与提示、错误 toast、说话状态上报、
 * 流式语音(TTS)的接线与在飞文本缓冲。
 *
 * <p>它直接订阅循环内核的 {@link LoopEvent}({@link #on}),内核只管什么时候调模型、派工具、停下来,
 * 不碰界面;这里只管"她看/听起来在干什么",不碰会话与队列。删掉这一层,对话照常进行,只是又聋又哑。
 */
final class TurnPresenter {

    /**
     * 一次调模型的语音接线:正文增量的去处 + 收尾动作打包。语音未配置时是
     * {@link #SILENT_VOICE}(两样都是空操作)。
     */
    private record VoiceTurn(Consumer<String> sink, Runnable finish) {}

    private static final VoiceTurn SILENT_VOICE = new VoiceTurn(delta -> {}, () -> {});

    private final UUID entityUuid;
    /** 内核此刻的快照:打字机看"是不是在等模型",说话位看"是不是在输出"。 */
    private final Supplier<LoopStatus> status;
    /** 人设名(可空);说话人显示名的第一优先。 */
    private final Supplier<String> personaName;

    /**
     * 本同伴的流式语音管线,懒创建:首次在声线库里 resolve 到这个 UUID 的
     * 绑定时才 new。未绑定 = 永远 null = 零开销。
     */
    private VoicePipeline voice;
    /** 这一次调模型的语音接线;{@link #beginTurn} 换上,{@link #endTurn} 收尾。 */
    private VoiceTurn voiceTurn = SILENT_VOICE;

    /** 在飞回复的已到 content 增量(流式打字机的数据源)。增量随内核的事件到达,只来自当前这次调用;
     *  回复落库/失败/切断时清空——committed 消息接管显示,永不双份。 */
    private final StringBuilder livePartial = new StringBuilder();
    /** 在飞回合的思考流(推理模型的 reasoning 增量)。与 {@link #livePartial} 同一套
     *  生命周期,落库后由 AssistantTurn 里那份 reasoning 接管显示,永不双份。 */
    private final StringBuilder liveReasoning = new StringBuilder();
    /** 上次刷进聊天框流式行的文本(变了才重刷,不逐 tick 折腾聊天框)。 */
    private String lastStreamedPartial = "";
    /** 上次发给服务端的说话状态(翻转才发包,不逐 tick 刷)。 */
    private boolean lastSpeakingSent;

    TurnPresenter(UUID entityUuid, Supplier<LoopStatus> status, Supplier<String> personaName) {
        this.entityUuid = entityUuid;
        this.status = status;
        this.personaName = personaName;
    }

    /** 内核的事件在这里变成主人看得见、听得见的东西。 */
    void on(LoopEvent event) {
        switch (event) {
            case LoopEvent.TurnStarted turn -> beginTurn(turn.ownerSpoke());
            case LoopEvent.ModelDelta delta -> delta(delta.content(), delta.reasoning());
            case LoopEvent.AssistantMessage message -> showReply(message.turn());
            case LoopEvent.RunEnded ended -> {
                if (!(ended.end() instanceof RunEnd.Done)) {
                    endTurn();   // 失败或被切断:半截文字作废、语音收尾(说完了的回复在落库时已经收过)
                }
            }
            case LoopEvent.TurnFailed failed -> showFailure(failed.words());
            case LoopEvent.HoldChanged changed -> {
                if (changed.hold() == Hold.BLOCKED) {
                    showBlocked(changed.reason());
                }
            }
            case LoopEvent.Halted halted -> {
                // 切断不管当时有没有 run 都会来:闲时按停止同样要让语音闭嘴、收起头顶的思考/残句
                interruptVoice();
                SpeechBubbles.clear(entityUuid);
            }
            case LoopEvent.RunStarted ignored -> { }
            case LoopEvent.ToolStarted ignored -> { }
            case LoopEvent.ToolFinished ignored -> { }
            case LoopEvent.ModelUsed ignored -> { }
            case LoopEvent.TranscriptBoundary ignored -> { }
        }
    }

    /** Live partial of the in-flight assistant reply ("" when idle) — GUI typewriter source. */
    String livePartial() {
        return livePartial.toString();
    }

    /** 在飞回合的思考流("" = 没有或已落库)——G 面板思考块的流式数据源。 */
    String liveReasoning() {
        return liveReasoning.toString();
    }

    /** 每 client tick:语音管线推进、说话状态上报、聊天框打字机。 */
    void tick() {
        if (voice != null) voice.tick();
        syncSpeakingState();
        streamToChat();
    }

    /**
     * 外接大脑替她说话(say 工具):头顶气泡 + 聊天栏定格行 + 外接现场记录 + 语音,走的全是内脑说话的
     * 同一套画法。语音按当前绑定现取声线,整段排到播放队尾——不开新轮、不清存量,连续的 say 自然连播;
     * 主人的停止键照样一刀切停。未绑声线 = 静默(气泡与聊天行照旧)。
     */
    void sayExternal(String text) {
        String shown = ChatDisplayModes.current().assistantText(text);
        if (shown.isBlank()) shown = text;   // 全是动作记号也别无声吞掉——原样示人
        McpTranscript.say(entityUuid, shown);
        spoke(shown);
        VoiceLibrary.Entry cfg = VoiceLibrary.instance().resolve(entityUuid);
        if (cfg == null) return;
        if (voice == null) voice = new VoicePipeline(entityUuid);
        voice.sayAppend(cfg, text);
    }

    /** 说话人显示名:人设名优先,否则花名册名。 */
    String speakerName() {
        String persona = personaName.get();
        return persona != null && !persona.isBlank()
                ? persona
                : String.valueOf(NumenRoster.instance().name(entityUuid));
    }

    // ---- 一次调模型 ----

    /**
     * 要调一次模型了:清掉上一次的半截文字,开一轮语音(若该同伴绑定了声线)。每次都
     * 重新 resolve——声线库/绑定的编辑下一次生效;开新轮会打断上一轮还在播的残句。
     *
     * @param ownerBargeIn 主人的话还没被回应(硬停上一轮);否则句界衔接
     */
    private void beginTurn(boolean ownerBargeIn) {
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
    private void delta(String content, String reasoning) {
        if (!content.isEmpty()) {
            livePartial.append(content);
            voiceTurn.sink().accept(content);
        }
        liveReasoning.append(reasoning);
    }

    /** 这次调模型结束了(回复落库、失败或被切断):语音收尾,半截文字作废,聊天框摘掉在飞行。 */
    private void endTurn() {
        voiceTurn.finish().run();
        voiceTurn = SILENT_VOICE;
        clearPartial();
        finishStreamLine();
    }

    /** 模型的一条回复落地:头顶气泡是回复的主显示(附近玩家都看得见),聊天框回显一份当日志。 */
    private void showReply(AssistantTurn turn) {
        endTurn();   // committed 消息接管显示,半截打字与在飞行摘掉
        String shown = ChatDisplayModes.current().assistantText(turn.content());
        if (!turn.hasToolCalls()) {
            Constants.LOG.info("[numen-entity#{}] assistant (final): {}", entityUuid, turn.content());
        }
        // 最终回复和开工前的顺嘴一句(tool_calls 旁附的 content)同一个画法:是话就上气泡 + 字幕行,
        // 超长折叠,悬停看全文,完整记录在 G 面板。开工前没话说就不动气泡——上一句正文泡留着走完
        // 生命周期,身体动起来本身就是反馈;最终回复滤完为空(全是动作记号)时收起思考泡。
        if (!shown.isBlank()) {
            spoke(shown);
        } else if (!turn.hasToolCalls()) {
            SpeechBubbles.clear(entityUuid);
        }
    }

    /**
     * 她说出口了——<b>唯一的一处</b>。头顶气泡是主显示,聊天框回显一份当日志,
     * 同一个会话里的其他同伴旁听到一份。
     *
     * <p>内脑回复与外脑 say 走同一条——"她说了什么"只能有一个出处,
     * 否则旁听到的和主人听见的会开始对不上。
     */
    private void spoke(String shown) {
        SpeechBubbles.say(entityUuid, shown);
        ChatLines.companion(speakerName(), shown);
        com.dwinovo.numen.client.agent.Conversations.instance().heard(entityUuid, shown);
        com.dwinovo.numen.client.notify.MessageNotices.spoke(entityUuid, shown);
    }

    /** 调用失败而且不再重试:必须让主人看见——沉进日志就是"已读不回"。 */
    private void showFailure(String words) {
        SpeechBubbles.clear(entityUuid);
        ChatLines.notice(speakerName(), "这次没连上(" + truncate(words, 90) + ")——稍后再试一句,详情见日志");
        // HUD toast:玩家多半没开面板(Y/V 快捷对话),这是唯一接得住他的通道。
        NumenHudToasts.push(NumenToasts.Severity.ERROR, speakerName() + ": " + truncate(words, 90));
    }

    /** 端点不可用:配置问题不能静默——快捷键用户不开面板,聊天栏警示行是唯一出口。 */
    private void showBlocked(String problem) {
        ChatLines.notice(speakerName(), truncate(problem, 160));
        SpeechBubbles.clear(entityUuid);
    }

    /** 语音闭嘴:停播 + 清队列(打断/死亡)。 */
    private void interruptVoice() {
        if (voice != null) voice.interrupt();
    }

    /** 半截打字作废:正文与思考流一起清。 */
    private void clearPartial() {
        livePartial.setLength(0);
        liveReasoning.setLength(0);
    }

    /** 聊天框的打字机:在飞回复逐 tick 长出来——不开面板也能实时看她说话。 */
    private void streamToChat() {
        if (status.get().phase() != Phase.MODEL || livePartial.length() == 0) {
            return;
        }
        String filtered = ChatDisplayModes.current().assistantText(livePartial.toString());
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

    /** 大脑在输出(思考/生成/跑工具/语音在播)→ 告诉身体,好在说话期间注视主人。整理记忆不算说话。 */
    private void syncSpeakingState() {
        // 退出游戏的最后几个 client tick 里连接已拆——此时发包会在
        // PacketDistributor.sendToServer 里 NPE 崩掉客户端。断线期不发,
        // 状态留在 lastSpeakingSent 里,重连后首次翻转自然补上。
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        Phase phase = status.get().phase();
        boolean speaking = phase == Phase.MODEL || phase == Phase.TOOLS || (voice != null && voice.isSpeaking());
        if (speaking != lastSpeakingSent) {
            lastSpeakingSent = speaking;
            Services.NETWORK.sendToServer(new com.dwinovo.numen.network.payload.SpeakingStatePayload(
                    entityUuid, speaking));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
