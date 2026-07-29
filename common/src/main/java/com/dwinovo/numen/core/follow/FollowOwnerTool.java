package com.dwinovo.numen.core.follow;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

/**
 * Body-bound LLM control for the current companion's owner-follow intent.
 */
public final class FollowOwnerTool implements NumenTool {

    public static final String TOOL_NAME = "follow_owner";

    private static final Set<String> ARGUMENTS = Set.of("action");

    private final FollowConfig config;
    private final ControlInvoker controlInvoker;

    public FollowOwnerTool(FollowConfig config) {
        this(config, FollowService::apply);
    }

    FollowOwnerTool(FollowConfig config, ControlInvoker controlInvoker) {
        this.config = Objects.requireNonNull(config, "config");
        this.controlInvoker =
                Objects.requireNonNull(controlInvoker, "controlInvoker");
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return """
                Control or inspect automatic owner-follow for this current companion body only.
                Call only when the user explicitly asks to control or query automatic following:
                “跟着我”/“开始跟随” = on; “以后别跟了”/“关闭自动跟随” = off;
                “先在这里等一下”/“暂时别跟” = pause; “继续跟我” = resume;
                “你为什么不动”/“跟随状态” = status.
                on/off/pause/resume must be performed with this tool; never merely claim the state changed.
                Do NOT use for ordinary goto or coordinate movement: “去某坐标” remains goto.
                Use resume for a vague “继续” only when context clearly means a paused follow.
                This tool changes state only. OwnerFollowChain performs movement in the background;
                it does not teleport the companion or move it immediately.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("action",
                        "Automatic-follow control: on, off, pause, resume, or status.",
                        "on", "off", "pause", "resume", "status")
                .build();
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer companion,
            Consumer<String> reply) {
        Objects.requireNonNull(reply, "reply");
        if (companion == null || companion.getOwnerUuid() == null) {
            reply.accept(TaskResult.fail(
                    "当前同伴没有可验证的主人绑定，无法控制自动跟随。").toJson());
            return;
        }
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            reply.accept(TaskResult.fail(
                    "当前同伴不在可用的服务器世界中。").toJson());
            return;
        }

        FollowAction action;
        try {
            action = parseArguments(args);
        } catch (IllegalArgumentException exception) {
            reply.accept(TaskResult.fail(exception.getMessage()).toJson());
            return;
        }

        FollowControlResult result =
                invokeControl(server, companion, action);
        reply.accept(resultJson(result));
    }

    static String resultJson(FollowControlResult result) {
        Map<String, Object> data = Map.of(
                "code", result.code(),
                "changed", result.changed(),
                "status", result.status().compactText());
        TaskResult taskResult = result.success()
                ? TaskResult.ok(result.message(), data)
                : TaskResult.fail(result.message(), data);
        return taskResult.toJson();
    }

    FollowControlResult invokeControl(
            MinecraftServer server,
            NumenPlayer companion,
            FollowAction action) {
        return controlInvoker.apply(server, companion, action, config);
    }

    static FollowAction parseArguments(JsonObject args) {
        if (args == null || !args.keySet().equals(ARGUMENTS)) {
            throw new IllegalArgumentException(
                    "follow_owner 只接受 action 参数。");
        }
        JsonElement element = args.get("action");
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(
                    "action 必须是 on、off、pause、resume 或 status。");
        }
        return FollowAction.parse(element.getAsString())
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知 action；只能使用 on、off、pause、resume 或 status。"));
    }

    @FunctionalInterface
    interface ControlInvoker {
        FollowControlResult apply(
                MinecraftServer server,
                NumenPlayer companion,
                FollowAction action,
                FollowConfig config);
    }
}
