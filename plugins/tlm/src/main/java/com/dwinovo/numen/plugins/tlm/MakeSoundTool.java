package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 出个声。<b>什么时候出、出哪一声,都由她自己判断</b>——这正是这个工具的意义。
 *
 * <p>能挑的都是<b>自主音</b>:下雨了应一声、早上打个招呼、干活时哼一句。挨打和死亡
 * 不在这儿——那是反应不是选择,列进来等于允许她无缘无故喊疼。见 {@link MaidVoice}。
 *
 * <p>这不是说话。说话有 TTS,这里是模型包自带的语音,跟着模型走。
 *
 * <h2>为什么不做成 list + play 两步</h2>
 * 哼一声是随口的动作。先查一遍再哼要两轮 LLM 往返,代价比动作本身还大,那点随口的
 * 意思就没了。能发哪些直接挂在她每轮的 {@code <maid_voice>} 里(见 {@link MaidLook}),
 * 一步就能发。模型清单不一样——换装是慎重动作,查一下正合适,那个才做成工具。
 */
public final class MakeSoundTool implements NumenTool {

    @Override
    public String name() {
        return "make_sound";
    }

    @Override
    public String description() {
        return "用当前模型自带的语音出个声(不是说话——说话照常说)。能发哪些看 <maid_voice>。"
             + "偶尔一次才有意思,别连着用。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        // 可选值不写进 schema:它跟着玩家装的包变,写进去等于天天把提示词缓存打穿。
        // 清单走 <maid_voice>,和模型清单不进工具描述是同一个道理。
        return Schema.object()
                .string("sound", "要发哪一声,从 <maid_voice> 里挑一个原样填")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        if (!Tlm.present()) {
            call.complete(TaskResult.fail("这里没装车万女仆").toJson());
            return;
        }
        UUID me = call.ctx().entityUuid();
        JsonObject args = call.args();
        String sound = args.has("sound") && !args.get("sound").isJsonNull()
                ? args.get("sound").getAsString().trim() : "";

        if (MaidVoice.speak(me, sound)) {
            call.complete(TaskResult.ok("发了一声:" + sound, Map.of("sound", sound)).toJson());
            return;
        }

        // 播不出去就如实说。谎报成功的话她会以为主人听见了,然后接着往下讲。
        Set<String> can = MaidVoice.voluntary(me);
        call.complete(TaskResult.fail(can.isEmpty()
                ? "这套模型没带语音,出不了声"
                : "这套模型发不了「" + sound + "」;能发的是 " + String.join("、", can)).toJson());
    }
}
