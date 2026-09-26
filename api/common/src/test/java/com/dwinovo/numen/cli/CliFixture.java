package com.dwinovo.numen.cli;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    /** 在主人客户端这一侧跑模型写的一行,和 {@code command} 工具在客户端做的一样。 */
    static Outcome onClient(String line) {
        Outcome out = new Outcome();
        NumenCli.run(line, new ClientSource(UUID.randomUUID(), out.replies::add, () -> out.forwarded = true));
        return out;
    }

    /**
     * 服务端那一侧跑一行第 1 层命令:经唯一的执行入口,在 Numen 服务端的树上解析、执行,处理函数拿到的是这次调用的源。
     * 和真服务器差的只有身体:测试的处理函数不碰身体,活体给 null。第 0 层(行首 {@code /})要真服务器,在 GameTest 里验。
     */
    static Outcome onServer(String line) {
        Outcome out = new Outcome();
        CommandRunner.line(new ServerSource(null, CommandTool.NAME, "test-call", CommandTool.args(line),
                out.replies::add), line);
        return out;
    }
}
