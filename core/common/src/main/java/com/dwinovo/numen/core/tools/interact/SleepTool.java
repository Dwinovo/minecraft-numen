package com.dwinovo.numen.core.tools.interact;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.tools.SleepOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 当场返回的身体动作:躺进手边的床,并确认真的睡着了。
 *
 * <p>走 {@code TaskDispatch} 的第一条道——它就是一次调用的事,没有旅程可等,所以不占任务槽。
 * <b>它到"躺下"为止就结束了</b>,不会挂着等天亮:她躺下之后模型该拿回控制权去说话或安排
 * 别的,而不是被一个几分钟的任务锁住。
 *
 * <p>不自己找床、不自己走路:那两件事 {@code scan_blocks} 和 {@code goto} 已经各有一个统一
 * 的实现,再包一份进来就是第三个入口。而且拆开之后模型看得见中间结果——床有几张、多远、
 * 走不走得过去,它能据此改主意;包成一个"去睡觉"的黑盒,这些它一个都不知道。
 */
public final class SleepTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final SleepOps impl = new SleepOps();

    private record Args(Integer x, Integer y, Integer z) {}

    @Override
    public String name() {
        return "sleep";
    }

    @Override
    public String description() {
        return "Get into a bed you are already standing next to, and report whether you are actually "
                + "asleep. It does NOT travel: find a bed with scan_blocks using #minecraft:beds (that "
                + "one tag covers every colour), goto it, then call this. Give x/y/z to use one specific "
                + "bed, or omit them for whichever bed is in reach. Succeeds only when the server "
                + "confirms you are sleeping; otherwise it hands back Minecraft's own reason — read it. "
                + "\"Only at night\" means wait (set_timer), not retry. \"Too far away\" means goto. "
                + "Returns the moment you lie down; night passes on its own and needs no tool.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("x", "X of the bed. Leave null to use whichever bed is in reach.")
                .nullableNumber("y", "Y of the bed. Leave null to use whichever bed is in reach.")
                .nullableNumber("z", "Z of the bed. Leave null to use whichever bed is in reach.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(a == null
                ? impl.sleep(null, null, null, self)
                : impl.sleep(a.x(), a.y(), a.z(), self));
    }
}
