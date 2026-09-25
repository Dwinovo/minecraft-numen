package com.dwinovo.numen.cli;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 命令行单测共用的几样:经插件那扇门登记、从两侧跑一行命令、读回执。命令树与工具表是进程级的静态表,各个测试类
 * 登记各自名字的组,互不相撞。
 */
final class CliFixture {

    private CliFixture() {}

    /** 插件拿到的那扇门——测试和插件走同一条路登记。 */
    static NumenApi door() {
        AtomicReference<NumenApi> api = new AtomicReference<>();
        NumenPlugins.register(api::set);
        return api.get();
    }

    /** 一次调用的回执与它有没有被送去服务端。 */
    static final class Outcome {
        final List<String> replies = new ArrayList<>();
        boolean forwarded;

        boolean success() {
            return json().get("success").getAsBoolean();
        }

        String message() {
            return json().get("message").getAsString();
        }

        JsonObject json() {
            if (replies.size() != 1) {
                throw new AssertionError("expected exactly one reply, got " + replies);
            }
            return JsonParser.parseString(replies.get(0)).getAsJsonObject();
        }
    }

    /** 在主人客户端这一侧跑一行,和 {@code command} 工具在客户端做的一样。 */
    static Outcome onClient(String line) {
        Outcome out = new Outcome();
        NumenCli.run(NumenCli.bare(line), new ClientSource(UUID.randomUUID(), out.replies::add,
                () -> out.forwarded = true));
        return out;
    }

    /**
     * 服务端那一侧的一行 Numen 命令:她在 MC 指令树 {@code /numen} 下的那些节点由同一个生成器长出来
     * ({@link NumenCli#nodes}),写不通的说法是执行入口给 Numen 命令的同一种({@link NumenCli#problem}),处理函数拿到
     * 的是这次调用的源。和真服务器差的只有两样,都在 GameTest 里对着真服务器验:这次调用放在她来源的回话去处里
     * ({@code Echo},要 mixin),以及权限层(要活世界)。测试的处理函数不碰身体,活体给 null。
     */
    static Outcome onServer(String typed) {
        Outcome out = new Outcome();
        String line = NumenCli.bare(typed);
        ServerSource call = new ServerSource(null, CommandTool.NAME, "test-call", CommandTool.args(line),
                out.replies::add);
        CommandDispatcher<Object> tree = new CommandDispatcher<>();
        LiteralArgumentBuilder<Object> root = LiteralArgumentBuilder.literal(NumenCli.ROOT);
        NumenCli.nodes(new CommandTree<Object>() {
            @Override
            void handle(Object source, Body body) throws CommandSyntaxException {
                body.run(call);
            }

            @Override
            boolean runs(Action action) {
                return action.runsOnServer();
            }
        }).forEach(root::then);
        tree.register(root);
        ParseResults<Object> parse = tree.parse(line, null);
        String problem = NumenCli.problem(parse, line);
        if (problem != null) {
            out.replies.add(TaskResult.fail(problem).toJson());
            return out;
        }
        try {
            tree.execute(parse);
        } catch (CommandSyntaxException e) {
            throw new AssertionError(line + " passed the check but did not run", e);
        }
        return out;
    }
}
