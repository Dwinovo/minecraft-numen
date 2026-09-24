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
 * 命令行单测共用的几样:经插件那扇门登记、从两侧的源对象跑一行命令、读回执。命令树与工具表是进程级的
 * 静态表,各个测试类登记各自名字的组,互不相撞。
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

    /** 在主人客户端这一侧跑一行。 */
    static Outcome onClient(String line) {
        Outcome out = new Outcome();
        NumenCli.run(line, new ClientSource(UUID.randomUUID(), out.replies::add, () -> out.forwarded = true));
        return out;
    }

    /** 在服务端这一侧跑一行(测试的处理函数不碰身体,活体给 null)。 */
    static Outcome onServer(String line) {
        Outcome out = new Outcome();
        JsonObject args = new JsonObject();
        args.addProperty("command", line);
        NumenCli.run(line, new ServerSource(null, NumenCli.ROOT, "test-call", args, out.replies::add));
        return out;
    }
}
