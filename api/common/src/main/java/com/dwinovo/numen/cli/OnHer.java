package com.dwinovo.numen.cli;

import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * 以服务器的权威、只对她自己执行第 0 层的指令。第 1 层的动作借服务器的权威,只有这一条路:声明了
 * {@link Authority#SERVER_ON_HER} 的动作,处理函数从 {@link ServerSource#onHer()} 拿到它。
 *
 * <p>作用对象写死在这里:调用方给的是目标之前的那一截({@code ysm model set})和之后的值,目标由这里写成她的名字,
 * 够不着别人。来源是服务器自己的({@code MinecraftServer#createCommandSourceStack},权限等级 4),回话由 {@link Echo}
 * 收下;要不要知会别的管理员照服务器的设定。
 *
 * <p>指令什么时候真正执行,看调用方在哪:不在任何一条指令的执行当中(服务器刻里跑的任务、网络包送来的调用)时,
 * {@link #run} 返回前它已经执行完;在另一条指令的执行当中时,原版的指令队列把它排到那条之后。第 1 层的处理函数与
 * 任务都不在指令的执行当中跑。
 */
public final class OnHer {

    private final NumenPlayer her;

    OnHer(NumenPlayer her) {
        this.her = her;
    }

    /**
     * 执行 {@code <command> <她> <values…>},返回指令对执行者说的每一句。
     *
     * @param command 目标之前的那一截,原样写进指令
     * @param values  目标之后的值,各自需要时加引号
     */
    public List<String> run(String command, String... values) {
        MinecraftServer server = her.getServer();
        Echo echo = new Echo(server.shouldInformAdmins());
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSource(echo).withCallback(echo), line(name(), command, values));
        return echo.lines();
    }

    /**
     * {@code <command> <她> <values…>} 之后接下来能写什么:补全引擎在这一格给的候选,还原成值本身(带引号的去掉引号)。
     * 模组自己维护的清单(有哪些模型、一个模型有哪些贴图)从这里问。
     */
    public List<String> next(String command, String... values) {
        CommandDispatcher<CommandSourceStack> dispatcher = her.getServer().getCommands().getDispatcher();
        String typed = line(name(), command, values) + " ";
        List<String> out = new ArrayList<>();
        for (String text : Completions.texts(Completions.at(
                dispatcher.parse(typed, her.getServer().createCommandSourceStack()), typed.length()))) {
            out.add(value(text));
        }
        return out;
    }

    private String name() {
        return her.getName().getString();
    }

    /** 这一行:目标之前的那一截、她、之后的值,值与她的名字需要时加引号(名字可能带空格或非英文)。 */
    static String line(String her, String command, String... values) {
        StringBuilder sb = new StringBuilder(command).append(' ').append(StringArgumentType.escapeIfRequired(her));
        for (String value : values) {
            sb.append(' ').append(StringArgumentType.escapeIfRequired(value));
        }
        return sb.toString();
    }

    /**
     * 一个候选在命令行上的写法还原成值:补全给带空格的值加的是 {@link StringArgumentType#escapeIfRequired} 那种引号,
     * 照它的反过来去掉。
     */
    static String value(String written) {
        if (written.length() < 2 || !written.startsWith("\"") || !written.endsWith("\"")) {
            return written;
        }
        return written.substring(1, written.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
