package com.dwinovo.numen.core.tools.inventory;
import com.dwinovo.numen.core.tools.ContainerOps;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * World-action tool (raw NumenTool): transfer items between slots in the open GUI. Runs as a short sync
 * task — taking something out of a container goes through the permission layer and may wait for the owner.
 */
public final class TransferTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final ContainerOps impl = new ContainerOps();

    private record Args(List<ContainerOps.Move> moves) {}

    @Override
    public String name() {
        return "transfer";
    }

    @Override
    public String description() {
        return "Transfer items between slots in the GUI you have open — reorganize, load a machine, "
                + "deposit or take. Pass `moves` as a LIST; they run in order, so do the whole job in one "
                + "call. inspect_gui first for slot indices.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .objectArray("moves", "Transfers to run in order (one whole job per call).", item -> item
                        .integer("from", "Source slot index (from inspect_gui).")
                        .nullableInteger("to", "Destination slot. OMIT to route the stack to the other section "
                                + "(deposit/take/feed). Give a slot to place exactly there (empty=move, same "
                                + "item=merge, different item=swap).")
                        .nullableInteger("count", "Exact number to move (needs `to`; default = the whole stack). "
                                + "Ignored when `to` is omitted — routing moves the whole stack."))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(self, impl.transfer(a.moves(), ctx(toolCallId, self)), reply);
    }
}
