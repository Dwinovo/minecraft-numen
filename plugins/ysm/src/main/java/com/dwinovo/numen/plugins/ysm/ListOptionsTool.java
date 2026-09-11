package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 现在穿什么、能换成什么、能做哪些动作——一次问清。
 *
 * <p>分成三个工具的话模型得连问三次才动得了手;而这三样本来就是同一个问题的三面。
 * 清单不写进工具描述里:描述是提示词前缀的一部分,跟着玩家装的模型变就会天天把
 * 缓存打穿。查询就该是查询。
 */
public final class ListOptionsTool implements NumenTool {

    private final Ysm ysm;
    private final YsmCatalog catalog;

    public ListOptionsTool(Ysm ysm, YsmCatalog catalog) {
        this.ysm = ysm;
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "list_ysm_options";
    }

    @Override
    public String description() {
        return "看自己现在穿的模型与贴图、能换的模型清单、当前模型自带的贴图与能做的动作。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args,
                             NumenPlayer companion, Consumer<String> reply) {
        var look = ysm.readLook(companion);
        var models = catalog.models();
        var textures = look == null ? java.util.List.<String>of() : catalog.textures(look.model());
        var emotes = catalog.emotesFor(look);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("current_model", look == null ? "(读不到,YSM 可能没装)" : look.model());
        data.put("current_texture", look == null ? "" : look.texture());
        data.put("available_models", models);
        data.put("textures", textures);   // 当前模型自带的贴图 id,switch_model 的 texture_id 从这里挑
        data.put("emotes", emotes);

        String summary = look == null
                ? "读不到当前模型,YSM 可能没装"
                : "现在穿 " + look.model() + ",可换 " + models.size()
                  + " 个模型,当前模型有 " + emotes.size() + " 个动作";
        reply.accept(TaskResult.ok(summary, data).toJson());
    }
}
