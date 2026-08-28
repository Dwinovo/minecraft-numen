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

    @Override
    public void invoke(ToolCall call) {
        if (!Tlm.present()) {
            call.complete(TaskResult.fail("这里没装车万女仆").toJson());
            return;
        }
        JsonObject args = call.args();
        String kind = args.has("kind") && !args.get("kind").isJsonNull()
                ? args.get("kind").getAsString() : "idle";
        String sound = switch (kind) {
            case "hurt" -> MaidVoice.HURT;
            case "attack" -> MaidVoice.ATTACK;
            default -> MaidVoice.IDLE;
        };
        MaidVoice.play(call.ctx().entityUuid(), sound);
        // 不回报"播成功了":没穿模型、包里没这条、人不在视距内都会静默跳过,
        // 而这三种情况都不是错误。硬说成功反而让她以为主人听见了。
        call.complete(TaskResult.ok("哼了一声", Map.of("kind", kind)).toJson());
    }
}
