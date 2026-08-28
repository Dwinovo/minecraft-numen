package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 换一身模型。
 *
 * <h2>能换成什么由 YSM 判,不由这里判</h2>
 * 命令刻意不传 {@code ignore_auth},YSM 会按同伴自己的授权表检查;而那张表由
 * {@link OwnerSync} 持续镜像成主人的。所以"主人没有的模型同伴也要不到"是 YSM 在
 * 拦——本工具不写这个 if,也就不会有"我们的判断和 YSM 的判断不一致"这种事。
 *
 * <h2>为什么要回读</h2>
 * 命令以服务器身份执行,它的成功/失败回执进的是服务器控制台,这里收不到。所以执行完
 * 回读一次同伴的 NBT:模型真变了才算成功。不回读的话,越权被拦时模型会以为自己换好了。
 */
public final class SwitchModelTool implements NumenTool {

    @Override
    public String name() {
        return "switch_model";
    }

    @Override
    public String description() {
        return "换一身模型(外观)。能换的范围跟主人一致——主人有授权的你才有。"
             + "用 list_ysm_options 看有哪些可选。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("model_id", "模型 id,形如 misc/1_alex 或 default")
                .optionalString("texture_id", "贴图 id;不传就用模型自带的默认贴图")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args,
                             NumenPlayer companion, Consumer<String> reply) {
        String model = args.has("model_id") ? args.get("model_id").getAsString() : "";
        if (model.isBlank()) {
            reply.accept(TaskResult.fail("要传 model_id").toJson());
            return;
        }
        var server = companion.getServer();
        if (server == null) {
            reply.accept(TaskResult.fail("身体不在服务端上").toJson());
            return;
        }
        String texture = args.has("texture_id") ? args.get("texture_id").getAsString() : null;

        Ysm.setModel(server, companion.getGameProfile().getName(),
                new Ysm.Look(model, texture));

        // 回读:以身体的实际状态为准,不信命令跑过就是成功了
        var now = Ysm.readLook(companion);
        if (now != null && model.equals(now.model())) {
            reply.accept(TaskResult.ok("换好了:" + model).toJson());
        } else {
            reply.accept(TaskResult.fail(
                    "没换成 '" + model + "'。要么这个模型不存在,要么主人没有它的授权;"
                  + "现在穿的还是 " + (now == null ? "(读不到)" : now.model())).toJson());
        }
    }
}
