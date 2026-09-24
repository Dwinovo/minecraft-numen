package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ServerToolTransport;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 一个动作提升成的快捷工具。名字与描述是登记时 {@link Action#promote} 写的,schema 由动作的参数表生成;
 * 调用它就是执行那个动作:JSON 参数按同一组参数类型读成值,交给同一个处理函数,回执也就是同一份。
 * 不拼命令字符串再解析一遍。
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
        action.execute(new ServerSource(companion, name(), toolCallId, args, reply),
                CommandArgs.fromJson(action.params(), args));
    }
}
