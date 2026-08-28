package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 让同伴做一个动作。
 *
 * <p>动作名不写死在这里:每个模型自带一套,清单由 {@link YsmCatalog} 从 YSM 的模型
 * 目录读出来,跟着玩家装了什么模型走。
 *
 * <p><b>音效不用我们管。</b> 模型作者可以把音效接在动画上(动画 JSON 里的
 * {@code sound_effects}),YSM 播动画时一并放。真机验过:同伴是服务端假玩家,
 * 但 YSM 照样给它放声音——播放路径没有区分真假玩家。所以这里只管发 play 命令。
 */
public final class PlayEmoteTool implements NumenTool {

    private static final String STOP = "stop";

    @Override
    public String name() {
        return "play_emote";
    }

    @Override
    public String description() {
        return "做一个动作/表情。动作来自当前模型自带的那套,先用 list_ysm_options 看有哪些。"
             + "传 \"" + STOP + "\" 停下当前动作,回到平时的待机。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("animation", "动作名,或 \"" + STOP + "\" 停止")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args,
                             NumenPlayer companion, Consumer<String> reply) {
        String animation = args.has("animation") ? args.get("animation").getAsString() : "";
        if (animation.isBlank()) {
            reply.accept(TaskResult.fail("要传 animation").toJson());
            return;
        }
        var server = companion.level().getServer();
        if (server == null) {
            reply.accept(TaskResult.fail("身体不在服务端上").toJson());
            return;
        }
        String me = companion.getName().getString();
        if (STOP.equalsIgnoreCase(animation)) {
            Ysm.stopAnimation(server, me);
            reply.accept(TaskResult.ok("停下了").toJson());
            return;
        }
        // YSM 的 play 命令是静默的:动作名不存在时它既不报错也不回执,所以先自己核一遍,
        // 否则模型会以为做了、其实什么都没发生。
        var known = YsmCatalog.emotesFor(Ysm.readLook(companion));
        if (!known.isEmpty() && !known.contains(animation)) {
            reply.accept(TaskResult.fail(
                    "当前模型没有 '" + animation + "' 这个动作,用 list_ysm_options 看有哪些").toJson());
            return;
        }
        Ysm.playAnimation(server, me, animation);
        reply.accept(TaskResult.ok("做了 " + animation).toJson());
    }
}
