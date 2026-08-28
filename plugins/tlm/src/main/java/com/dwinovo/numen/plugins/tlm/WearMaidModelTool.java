package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 换一套女仆模型,或者脱下来变回本来的样子。
 *
 * <p>只认清单里真实存在的 id。模型不存在时直接失败并把清单带回去——比默默换成
 * 一个空模型好:同伴会知道自己刚才那句没生效,下一轮能自己改口。
 */
public final class WearMaidModelTool implements NumenTool {

    @Override
    public String name() {
        return "wear_maid_model";
    }

    @Override
    public String description() {
        return "换上某套车万女仆模型,或脱下它。model 留空 = 脱下,身体交还给别的外观(YSM 的模型、或者你本来的皮肤)。穿着的时候它盖住整个身体,别的外观露不出来。id 必须先用 list_maid_models 查到,别猜。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("model", "女仆模型 id;留空表示脱下,把身体交还给别的外观")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        if (!Tlm.present()) {
            call.complete(TaskResult.fail("这里没装车万女仆,换不了模型").toJson());
            return;
        }

        JsonObject args = call.args();
        UUID me = call.ctx().entityUuid();
        String model = args.has("model") && !args.get("model").isJsonNull()
                ? args.get("model").getAsString().trim() : "";

        if (model.isEmpty()) {
            Wardrobe.wear(me, null);
            call.complete(TaskResult.ok("脱下了,身体交还给别的外观", Map.of()).toJson());
            return;
        }

        if (!Tlm.exists(model)) {
            // 不把全量清单塞回去(两百多个,一次两万 token),指回 list_maid_models 去搜
            call.complete(TaskResult.fail(
                    "没有叫 " + model + " 的模型;用 list_maid_models 带 search 搜一下正确的 id").toJson());
            return;
        }

        Wardrobe.wear(me, model);
        String name = MaidCatalog.nameOf(model);
        call.complete(TaskResult.ok("换上了 " + name,
                Map.of("current_model", model, "current_name", name)).toJson());
    }
}
