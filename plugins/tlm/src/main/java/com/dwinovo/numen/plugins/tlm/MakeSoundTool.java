package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * 出个声。<b>什么时候用由她自己判断</b>——这正是这个工具存在的理由。
 *
 * <p>另一条路是"闲着就定时哼一声",但七个音循环播,一个宠物在你旁边一直响,
 * 很快就从"有生气"变成"烦"。交给她之后,频率跟着语境走:主人夸了她应一声、
 * 干完活哼一声——而判断语境本来就是大模型擅长的事,还省掉了"她现在忙不忙"那套判据。
 *
 * <p>这不是说话。说话有 TTS,这里是模型包自带的语音,跟着模型走。
 */
public final class MakeSoundTool implements NumenTool {

    @Override
    public String name() {
        return "make_sound";
    }

    @Override
    public String description() {
        return "用当前女仆模型自带的语音哼一声(不是说话——说话照常说)。"
             + "高兴、应答、干完活的时候用得上;别连着用,偶尔一次才有意思。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("kind", "哪一类;留空是随口哼一声", "idle", "hurt", "attack")
                .build();
    }

    /** 类别名 ↔ 声音 id。这是两者对应关系的唯一一处。 */
    private static final Map<String, String> KINDS = Map.of(
            "idle", MaidVoice.IDLE,
            "hurt", MaidVoice.HURT,
            "attack", MaidVoice.ATTACK);

    /** 这只同伴当前模型<b>真正带得动</b>的类别。喂给 runtime_state,让她每轮都知道。 */
    static java.util.List<String> kindsOf(java.util.UUID companion) {
        var have = MaidVoice.available(companion);
        return KINDS.entrySet().stream()
                .filter(e -> have.contains(e.getValue()))
                .map(Map.Entry::getKey).sorted().toList();
    }

    @Override
    public void invoke(ToolCall call) {
        if (!Tlm.present()) {
            call.complete(TaskResult.fail("这里没装车万女仆").toJson());
            return;
        }
        java.util.UUID me = call.ctx().entityUuid();
        JsonObject args = call.args();
        String kind = args.has("kind") && !args.get("kind").isJsonNull()
                ? args.get("kind").getAsString() : "idle";
        String sound = KINDS.get(kind);
        if (sound == null) {
            call.complete(TaskResult.fail("没有 " + kind + " 这一类").toJson());
            return;
        }

        // 播不出去就如实说。上一版无论如何都回"哼了一声"——没穿模型、包里没这条、
        // 人不在视距内全被说成成功,她会以为主人听见了,然后接着往下讲。
        if (!MaidVoice.play(me, sound)) {
            var can = kindsOf(me);
            call.complete(TaskResult.fail(can.isEmpty()
                    ? "这套模型没带语音,出不了声"
                    : "这套模型没有 " + kind + " 这一类;能用的是 " + String.join("、", can)).toJson());
            return;
        }
        call.complete(TaskResult.ok("哼了一声", Map.of("kind", kind)).toJson());
    }
}
