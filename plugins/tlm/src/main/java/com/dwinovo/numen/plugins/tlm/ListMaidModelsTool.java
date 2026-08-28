package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 现在穿什么、这里装了哪些女仆模型。
 *
 * <p>不带关键词只给包级摘要,带关键词才展开具体条目——这台机器上有两百多个模型,
 * 全量倒出去一次吃掉两万多 token,而且给的是一堆哈希 id,模型拿到了也讲不清
 * 哪个是哪个。理由与封顶细节见 {@link MaidCatalog}。
 *
 * <h2>为什么不走身体</h2>
 * 模型包只有客户端知道({@code CustomPackLoader} 是客户端类),发去服务端问,
 * 服务端也答不上来。所以覆写 {@link #invoke} 当场答完。
 */
public final class ListMaidModelsTool implements NumenTool {

    @Override
    public String name() {
        return "list_maid_models";
    }

    @Override
    public String description() {
        return "看自己现在穿的车万女仆模型,以及这里装了哪些。不填 search 给各模型包的概览,"
             + "填了(角色名/包名都行)才列出具体条目。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("search", "按角色名、包名或 id 搜;留空则给各包的概览")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        if (!Tlm.present()) {
            call.complete(TaskResult.fail("这里没装车万女仆,换不了模型").toJson());
            return;
        }

        JsonObject args = call.args();
        String q = args.has("search") && !args.get("search").isJsonNull()
                ? args.get("search").getAsString().trim() : "";

        String wornId = Wardrobe.worn(call.ctx().entityUuid());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("current_model", wornId == null ? null : wornId);
        data.put("current_name", wornId == null ? null : MaidCatalog.nameOf(wornId));

        String worn = wornId == null ? "现在是本来的样子" : "现在穿 " + MaidCatalog.nameOf(wornId);

        if (q.isEmpty()) {
            Map<String, Object> packs = MaidCatalog.summary();
            int total = packs.values().stream()
                    .mapToInt(v -> (int) ((Map<?, ?>) v).get("count")).sum();
            data.put("packs", packs);
            data.put("total", total);
            call.complete(TaskResult.ok(worn + ";一共 " + total + " 个模型,分在 " + packs.size()
                    + " 个包里。想找具体哪个,用 search 搜角色名或包名", data).toJson());
            return;
        }

        List<MaidCatalog.Entry> hits = MaidCatalog.search(q);
        List<Map<String, String>> rows = new ArrayList<>();
        for (MaidCatalog.Entry e : hits) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("id", e.id());
            r.put("name", e.name());
            r.put("pack", e.pack());
            rows.add(r);
        }
        data.put("matches", rows);
        call.complete(TaskResult.ok(worn + ";搜「" + q + "」找到 " + rows.size() + " 个", data).toJson());
    }
}
