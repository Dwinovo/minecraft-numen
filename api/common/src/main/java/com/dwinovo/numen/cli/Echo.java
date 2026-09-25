package com.dwinovo.numen.cli;

import com.dwinovo.numen.mixin.CommandSourceStackAccessor;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 她执行一行指令时 {@code CommandSourceStack} 的回话去处,同时带着这次调用({@link ServerSource}:调用 id、任务名、
 * 回信口)。执行入口构造她的来源时放进去({@link CommandRunner}),{@code execute} 这类改写来源的指令只换位置、朝向、
 * 实体,回话去处原样传下去,所以指令树上任何一格都从来源里取得到它。
 *
 * <ul>
 *   <li><b>{@code /numen} 的节点</b>从这里取出调用交给处理函数({@link #of}),处理函数自己回执或把活交给任务槽;
 *       正常返回就记下"这次调用 Numen 答了"({@link #answered})。</li>
 *   <li><b>原版与模组的指令</b>说的每一句(成功的、失败的)按顺序收下;结果由执行完的那一刻回调:成功与否、指令返回的数
 *       (分叉的指令每一支各回一次,有一支成功就算成功,数相加)。解析不通、抛了异常、被别的模组拦下时没有回调,
 *       就是没跑成。执行入口在指令跑完后把这些收成回执({@link #settle})。</li>
 * </ul>
 *
 * <p>成功的回话一律收下:原版按 {@code sendCommandFeedback} 决定要不要在聊天栏里给人看,那是给人看的开关,
 * 回执不是聊天栏。要不要知会别的管理员照她这具身体的来——那是服务器的审计,不因为换了去处而变。
 */
final class Echo implements CommandSource, CommandResultCallback {

    private final ServerSource call;
    private final List<String> lines = new ArrayList<>();
    private boolean ran;
    private boolean anySuccess;
    private int result;
    private boolean answered;

    Echo(ServerSource call) {
        this.call = call;
    }

    /**
     * 来源里带着的这次调用。她的来源只由执行入口构造;{@code /numen} 的节点只给她用,她又只经执行入口执行指令——
     * 取不到就是有人绕开了执行入口,当场说出来。
     */
    static Echo of(CommandSourceStack source) {
        if (((CommandSourceStackAccessor) source).numen$source() instanceof Echo echo) {
            return echo;
        }
        throw new IllegalStateException("a /numen command ran outside her command entry, as "
                + source.getTextName());
    }

    ServerSource call() {
        return call;
    }

    /** {@code /numen} 的处理函数正常返回:这次调用由它回执(当场回,或交给了任务槽)。 */
    void answered() {
        answered = true;
    }

    /**
     * 指令跑完了:没有 {@code /numen} 的处理函数答这次调用,就把指令说的话与结果收成回执送回。
     *
     * @param more 跑成了时接在指令原话后面的那一截(只在跑成时才去取);没有就是空串
     */
    void settle(String line, Supplier<String> more) {
        if (answered) {
            return;
        }
        Map<String, Object> data = Map.of("command", "/" + line, "output", List.copyOf(lines), "result", result);
        call.reply((ran && anySuccess
                ? TaskResult.ok("ran /" + line + ": " + said() + more.get(), data)
                : TaskResult.fail("/" + line + " failed: " + said(), data)).toJson());
    }

    /** 指令说的全部,一句一行;一句没说是 {@code (no output)}。 */
    private String said() {
        return lines.isEmpty() ? "(no output)" : String.join("\n", lines);
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
        return call.companion().shouldInformAdmins();
    }

    @Override
    public void onResult(boolean success, int value) {
        ran = true;
        anySuccess |= success;
        result += value;
    }
}
