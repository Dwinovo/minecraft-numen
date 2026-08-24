package com.dwinovo.numen.core.tools.perception;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.tools.ScaffoldOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 登记工具(当场返回):管她愿意拿来垫路的方块清单。不占身体。
 *
 * <p>每次调用——包括只读的那次——都把<b>当前完整清单</b>和<b>背包里还没进清单的方块</b>
 * 一起返回,所以读一次就够做决定,不用再去查背包。
 */
public final class ScaffoldMaterialsTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final ScaffoldOps impl = new ScaffoldOps();

    private record Args(String action, List<String> block_ids) {}

    @Override
    public String name() {
        return "scaffold_materials";
    }

    @Override
    public String description() {
        return "Manage which blocks you are willing to spend as throwaway scaffolding — the blocks "
                + "pathfinding sacrifices to pillar up, bridge a gap or step over a ledge. This is a "
                + "standing choice that persists across sessions and is used by every move you make, "
                + "including reflexes like fleeing. Call it with no action to just look. Anything you "
                + "list WILL be consumed and never comes back, so list what you consider junk here and "
                + "now: cobblestone is junk in a mineshaft and precious in the End. The reply always "
                + "carries the full current list plus what you are carrying that is not on it, so one "
                + "call is enough to decide. Emptying the list is a real choice — do it when what you "
                + "carry is earmarked for something (the dirt is for a build), and accept that she "
                + "then cannot pillar or bridge at all. Your owner is told when you change it.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action", "add appends, delete removes, set replaces the whole list, "
                        + "clear empties it so nothing at all may be spent. Omit to just read.",
                        "add", "delete", "set", "clear")
                .optionalStringArray("block_ids", "Namespaced block ids, e.g. "
                        + "['minecraft:cobbled_deepslate']. Required for add / delete / set; "
                        + "ignored by clear.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.apply(
                a == null ? null : a.action(),
                a == null ? null : a.block_ids(),
                self));
    }
}
