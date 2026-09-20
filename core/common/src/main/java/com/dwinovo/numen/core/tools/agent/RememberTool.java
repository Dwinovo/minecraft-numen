package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.tools.AgentOps;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.Map;

/**
 * Client-local tool (raw NumenTool): write one note to the companion's own memory.
 *
 * <p>记什么、不记什么写在系统提示词的 {@code <memory>} 那一层,这里只讲参数怎么填——
 * 同一份说明不写两处。
 */
public final class RememberTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final AgentOps impl = new AgentOps();

    private record Args(String name, String description, String type, String content) {}

    @Override
    public String name() {
        return "remember";
    }

    @Override
    public String description() {
        return """
                Write one note to your own memory. It outlives this session and comes back to you \
                as a line in <memory>. Writing the same name again replaces that note.
                The description IS that line, and for most notes it is the whole note — put the \
                fact in it ("main base -340,68,120, door faces east"), not a label ("about the \
                base"). Use content only when there is more worth reading later; you get it back \
                with recall.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("name", "Short kebab-case handle for this note, e.g. main-base. "
                        + "Reusing one replaces that note.")
                .string("description", "The one line you will see in your index — the fact itself.")
                .enumStr("type", "owner: the owner's habits and wishes. world: places and things "
                        + "you have seen. lesson: something you tried that did not work.",
                        "owner", "world", "lesson")
                .optionalString("content", "Longer body, read back with recall. Leave out when "
                        + "the description already says everything.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            call.complete(impl.remember(call.ctx().entityUuid(), a.name(), a.description(),
                    a.type(), a.content()));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
