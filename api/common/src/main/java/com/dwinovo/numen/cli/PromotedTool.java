package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ServerToolTransport;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 一个动作提升成的快捷工具,是那条 {@code /numen} 命令的 alias。名字与描述是登记时 {@link Action#promote} 写的,
 * schema 由动作的参数表生成;调用它就是执行那个动作:JSON 参数按同一组参数类型读成值,交给同一个处理函数,回执也就是
 * 同一份。不拼命令字符串再解析一遍。服务端动作和 {@code command} 工具走同一个执行入口,过的是同一道权限。
 */
final class PromotedTool implements NumenTool {

    private final Action action;

    PromotedTool(Action action) {
        this.action = action;
    }

    @Override
    public String name() {
        return action.toolName();
    }

    @Override
    public String description() {
        return action.toolDescription();
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Param.schemaOf(action.params());
    }

    /**
     * 服务端动作照身体工具的默认路子:调用原样送去服务端,参数在那边读——读错的回执与所有身体工具同一种说法
     * (见 {@link NumenTool#serve})。客户端动作在这里当场读参数、当场执行。
     */
    @Override
    public void invoke(ToolCall call) {
        if (action.runsOnServer()) {
            ServerToolTransport.ship(call);
            return;
        }
        action.execute(ClientSource.of(call), CommandArgs.fromJson(action.params(), call.args()));
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        CommandArgs values = CommandArgs.fromJson(action.params(), args);
        CommandRunner.action(new ServerSource(companion, name(), toolCallId, args, reply), action, values, line(args));
    }

    /**
     * 这次调用写成命令是哪一行:必填参数按顺序,给了的标志写成 {@code --name value},每个值写成它在命令行上的样子。
     * 权限层裁决的、征询卡片上给主人看的就是它。参数已经读过一遍,到这里每个值都是一个合法的字面值。
     */
    private String line(JsonObject args) {
        StringBuilder sb = new StringBuilder(action.path());
        for (Param<?> p : action.params()) {
            JsonElement value = args.get(p.name());
            if (value == null || value.isJsonNull()) {
                continue;
            }
            sb.append(' ');
            if (!p.required()) {
                sb.append("--").append(p.name()).append(' ');
            }
            sb.append(p.type().written(value.getAsString()));
        }
        return sb.toString();
    }
}
