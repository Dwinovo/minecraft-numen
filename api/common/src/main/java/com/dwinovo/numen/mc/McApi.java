package com.dwinovo.numen.mc;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.ServerCall;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code numen.mc}:原版与模组的指令,写法和玩家在聊天栏里敲的一样(前面的 {@code /} 可写可不写),以她自己的权限执行。
 *
 * <ol>
 *   <li><b>先解析</b>:以她的 {@code CommandSourceStack} 在服务器的指令树上解析。写不通(没有这条、服务器不让她用、参数写错)当场
 *       {@code bad_argument} 并附上用法,不打扰主人。能用哪些是服务器按她的权限等级定的,这里不放宽也不收紧。</li>
 *   <li><b>过权限层</b>:执行一行指令是动作 {@code command(根名)},放行、问主人、拒绝由权限层裁决。</li>
 *   <li><b>执行</b>:{@link Commands#performPrefixedCommand},和玩家在聊天栏里敲的是同一条路,加载器的指令事件照常。来源是她自己的,
 *       只把回话去处换成 {@link Echo}。</li>
 *   <li><b>值</b>:指令说的每一句与它返回的数;{@code help <指令>} 在原版那一行用法之后接上从 Brigadier 挖出的参数类型、例子与此刻
 *       的候选({@link BrigadierHelp})。没跑成是 {@code failed},数据里是同样的那几样。</li>
 * </ol>
 */
public final class McApi {

    /** 原版的 {@code help}:不带参数列她此刻能执行的指令,带一条指令给出它的用法。 */
    private static final String HELP = "help";

    private McApi() {}

    /** 跑的那一行。 */
    public record Run(@Doc("A Minecraft or mod command exactly as a player types it in chat; the leading / may be "
            + "left out.") String command) {}

    /**
     * 一条指令跑完说了什么。
     *
     * @param command 跑的那一行,带 {@code /}
     */
    public record Ran(@Doc("The command as it ran, with its /.") String command,
                      @Doc("What it said, line by line.") List<String> output,
                      @Doc("The number the command returned.") int result) {}

    @Fn("Run one Minecraft or mod command and return what it said.")
    @Example("numen.mc.run(\"help give\")")
    @Example("numen.mc.run(\"time query daytime\")")
    @Note("The server decides which commands you may use at your permission level; `numen.mc.run(\"help\")` lists "
            + "them, and `numen.mc.run(\"help give\")` shows one's usage with the types, examples and what you could "
            + "write next.")
    @Note("A command may need your owner's consent first; the call waits for the answer.")
    public static Pending<Ran> run(ServerCall call, Run args) {
        String line = args.command().strip();
        if (line.startsWith("/")) {
            line = line.substring(1).strip();
        }
        if (line.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "there is no command to run.", Call.of("numen.mc.run", HELP));
        }
        NumenPlayer her = call.her();
        CommandDispatcher<CommandSourceStack> dispatcher = her.getServer().getCommands().getDispatcher();
        String problem = problem(dispatcher, line, her.createCommandSourceStack());
        if (problem != null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, problem, null);
        }
        String ran = line;
        return call.authorize(Action.command(line, dispatcher.getRoot())).then(allowed -> perform(her, ran));
    }

    /**
     * 这一行指令她此刻写不写得通;写得通返回 null。和服务器执行前做的是同一道检查,写不通时依次是 {@code error:} 为什么——根不存在、
     * 服务器不让她用这条、还是参数写错;{@code usage:} 这条的用法;{@code hint:} "你是不是要写"({@link Completions#didYouMean}),没有
     * 就是原版的 {@code help}。
     */
    public static String problem(CommandDispatcher<CommandSourceStack> dispatcher, String line, CommandSourceStack her) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(line, her);
        try {
            Commands.validateParseResults(parse);
            if (ContextChain.tryFlatten(parse.getContext().build(line)).isEmpty()) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand()
                        .createWithContext(parse.getReader());
            }
            return null;
        } catch (CommandSyntaxException e) {
            String helpHint = "`" + Call.of("numen.mc.run", HELP) + "` lists the commands you can run.";
            String root = line.split(" ", 2)[0];
            CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(root);
            String nearest = Completions.didYouMean(parse);
            String hint = nearest.isEmpty() ? helpHint : nearest;
            if (node == null) {
                return Problem.of("there is no /" + root + " command on this server", null, hint);
            }
            if (!node.canUse(her)) {
                return Problem.of("the server does not let you use /" + root, null, helpHint);
            }
            return Problem.of(e.getMessage(), Call.of("numen.mc.run",
                    dispatcher.getSmartUsage(dispatcher.getRoot(), her).get(node)), hint);
        }
    }

    /**
     * 以她的身份执行,回话去处换成 {@link Echo}。{@code help <指令>} 跑成了,原版那一行用法之后接上从 Brigadier 挖出的几行。
     *
     * @throws ApiError {@link ErrorKind#FAILED}:没跑成,说它说了什么
     */
    private static Ran perform(NumenPlayer her, String line) {
        Echo echo = new Echo(her.shouldInformAdmins());
        CommandDispatcher<CommandSourceStack> dispatcher = her.getServer().getCommands().getDispatcher();
        her.fakeClient().runCommand(echo::toHer, () -> her.getServer().getCommands().performPrefixedCommand(
                her.createCommandSourceStack().withSource(echo).withCallback(echo), line));
        List<String> output = new ArrayList<>(echo.lines());
        Ran ran = new Ran("/" + line, output, echo.result());
        if (!echo.succeeded()) {
            throw new ApiError(ErrorKind.FAILED, "/" + line + " failed: "
                    + (output.isEmpty() ? "(no output)" : String.join("\n", output)), null, ran);
        }
        String[] words = line.split(" ", 2);
        if (words[0].equals(HELP) && words.length == 2) {
            String more = BrigadierHelp.mine(dispatcher, words[1], her.createCommandSourceStack());
            more.lines().filter(l -> !l.isBlank()).forEach(output::add);
        }
        return new Ran(ran.command(), List.copyOf(output), ran.result());
    }
}
