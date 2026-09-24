package com.dwinovo.numen.core.task.command;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code numen mc} 在身体上的那一下:把这条指令交给权限层,放行就以她的身份交给服务器的指令调度器执行,
 * 要问就等主人答复(这次调用悬着),不许就带着理由收场。
 *
 * <p>执行走 {@link Commands#performPrefixedCommand},和玩家在聊天栏里敲的是同一条路:服务器的解析、执行、
 * 加载器的指令事件(别的模组在那里拦或记指令)都照常。来源是她自己的 {@code CommandSourceStack},只把回话的
 * 去处换成 {@link Echo}——指令说的每一句都收进回执。
 */
public final class McCommandCompanionTask extends AbstractCompanionTask<McCommandTaskRecord> {

    private Echo echo;

    public McCommandCompanionTask(NumenPlayer player, McCommandTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        tryRun();
    }

    @Override
    protected TaskState onTick() {
        return tryRun();
    }

    /**
     * 动手前问权限层:动作认的根名在服务器此刻的指令树上算(别名同认)。放行就执行、当刻收场;要问就等;不许就收场。
     */
    private TaskState tryRun() {
        Commands commands = player.getServer().getCommands();
        Permit permit = permit(Action.command(r.line, commands.getDispatcher().getRoot()));
        if (permit.state() == PermitState.WAITING) {
            return TaskState.RUNNING;
        }
        if (permit.state() == PermitState.REFUSED) {
            fail("did not run /" + r.line + ": " + permit.refusal(), FailureType.REFUSED);
            return TaskState.FAILED;
        }
        echo = new Echo(player);
        commands.performPrefixedCommand(player.createCommandSourceStack().withSource(echo).withCallback(echo),
                r.line);
        if (echo.succeeded()) {
            succeed();
            return TaskState.SUCCESS;
        }
        fail("/" + r.line + " failed: " + echo.said(), FailureType.UNKNOWN);
        return TaskState.FAILED;
    }

    /** 不动导航,没有要放的东西。 */
    @Override
    protected void cleanup() {}

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("command", "/" + r.line);
        if (echo != null) {
            data.put("output", List.copyOf(echo.lines));
            data.put("result", echo.result);
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return "ran /" + r.line + ": " + echo.said();
    }

    @Override
    protected String timeoutMessage() {
        return "/" + r.line + " timed out unexpectedly";
    }

    @Override
    protected String cancelledMessage() {
        return "/" + r.line + " was interrupted before it ran";
    }

    /**
     * 指令回话的去处与结果的回调。指令对来源说的每一句(成功的、失败的)按顺序收下;结果由执行完的那一刻回调:
     * 成功与否、指令返回的数(分叉的指令每一支各回一次,有一支成功就算成功,数相加)。解析不通、抛了异常、
     * 被别的模组拦下时没有回调,就是没跑成。
     *
     * <p>成功的回话一律收下:原版按 {@code sendCommandFeedback} 决定要不要在聊天栏里给人看,那是给人看的开关,
     * 回执不是聊天栏。要不要知会别的管理员照她这具身体的来——那是服务器的审计,不因为换了去处而变。
     */
    private static final class Echo implements CommandSource, CommandResultCallback {

        private final CommandSource body;
        private final List<String> lines = new ArrayList<>();
        private boolean ran;
        private boolean anySuccess;
        private int result;

        Echo(CommandSource body) {
            this.body = body;
        }

        @Override
        public void sendSystemMessage(Component message) {
            lines.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return body.shouldInformAdmins();
        }

        @Override
        public void onResult(boolean success, int value) {
            ran = true;
            anySuccess |= success;
            result += value;
        }

        boolean succeeded() {
            return ran && anySuccess;
        }

        /** 指令说的全部,一句一行;一句没说是 {@code (no output)}。 */
        String said() {
            return lines.isEmpty() ? "(no output)" : String.join("\n", lines);
        }
    }
}
