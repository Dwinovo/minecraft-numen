package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): read the owner's current status. */
public final class GetOwnerStatusTool implements NumenTool {

    private final PerceptionTools impl = new PerceptionTools();

    @Override
    public String name() {
        return "get_owner_status";
    }

    @Override
    public String description() {
        return "Read your owner's current status: name, online state, HP, hunger, position, "
                + "distance from you, and held item. Call before any 'follow', 'protect', or "
                + "'rendezvous' decision. If the owner is offline the call returns online:false "
                + "— default to autonomous mode until they return. No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        reply.accept(impl.getOwnerStatus(self));
    }
}
