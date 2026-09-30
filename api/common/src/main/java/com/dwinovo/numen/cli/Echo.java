package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 执行一行第 0 层指令时来源的回话去处:指令说的每一句(成功的、失败的,以及执行期间直接对她说的,见 {@link #toHer})
 * 按顺序收下;结果由执行完的那一刻回调:成功与否、
 * 指令返回的数(分叉的指令每一支各回一次,有一支成功就算成功,数相加)。解析不通、抛了异常、被别的模组拦下时没有回调,
 * 就是没跑成。执行入口在指令跑完后把这些收成回执({@link #receipt});借服务器权威的那条路({@link OnHer})把指令说的话
 * 交回处理函数({@link #lines})。
 *
 * <p>成功的回话一律收下:原版按 {@code sendCommandFeedback} 决定要不要在聊天栏里给人看,那是给人看的开关,
 * 回执不是聊天栏。要不要知会别的管理员照执行它的那一方来(她这具身体,或服务器)——那是服务器的审计,不因为换了去处
 * 而变。
 */
final class Echo implements CommandSource, CommandResultCallback {

    private final boolean informAdmins;
    private final List<String> lines = new ArrayList<>();
    private boolean ran;
    private boolean anySuccess;
    private int result;

    /** @param informAdmins 执行它的那一方要不要知会别的管理员 */
    Echo(boolean informAdmins) {
        this.informAdmins = informAdmins;
    }

    /**
     * 指令跑完了,把它说的话与结果收成一条回执。
     *
     * @param more 跑成了时接在指令原话后面的那一截(只在跑成时才去取);没有就是空串
     */
    String receipt(String line, Supplier<String> more) {
        Map<String, Object> data = Map.of("command", "/" + line, "output", List.copyOf(lines), "result", result);
        return (ran && anySuccess
                ? TaskResult.ok("ran /" + line + ": " + said() + more.get(), data)
                : TaskResult.fail("/" + line + " failed: " + said(), data)).toJson();
    }

    /** 指令说的每一句,按顺序。 */
    List<String> lines() {
        return List.copyOf(lines);
    }

    /** 指令说的全部,一句一行;一句没说是 {@code (no output)}。 */
    private String said() {
        return lines.isEmpty() ? "(no output)" : String.join("\n", lines);
    }

    @Override
    public void sendSystemMessage(Component message) {
        lines.add(message.getString());
    }

    /**
     * 执行期间服务端直接对她说的一句(不经来源:指令对目标说的话、{@code tellraw} 给她的),也是这条指令说的,按顺序收下。
     * 原版抄给管理员的审计({@code chat.type.admin},"[谁: 做了什么]")不收:那是来源已经收下的同一句的抄本,她是管理员时
     * 才会抄到她这儿。
     */
    void toHer(Component message) {
        if (message.getContents() instanceof TranslatableContents said && ADMIN_AUDIT.equals(said.getKey())) {
            return;
        }
        lines.add(message.getString());
    }

    /** 原版把指令说的话抄给管理员时用的那个翻译键({@code CommandSourceStack.broadcastToAdmins})。 */
    private static final String ADMIN_AUDIT = "chat.type.admin";

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
        return informAdmins;
    }

    @Override
    public void onResult(boolean success, int value) {
        ran = true;
        anySuccess |= success;
        result += value;
    }
}
