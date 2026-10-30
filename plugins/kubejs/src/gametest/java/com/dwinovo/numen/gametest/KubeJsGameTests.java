package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.plugin.PluginRoster;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import dev.latvian.mods.kubejs.KubeJSPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * KubeJS 在场时,服务端脚本里声明的 Numen 插件:她调得到脚本写的函数;改了脚本、{@code /reload} 之后她调到的是新写的,旧的函数不在了;
 * 一个声明出错的脚本插件在花名册里记为失败,不连累别的;脚本删了、重载,脚本插件整个撤回。都从入口调(她的 Lua 程序、真的 {@code /reload}),
 * 脚本写在运行目录的 {@code kubejs/server_scripts} 里。
 *
 * <p>只在挂着 KubeJS 的那一次跑批里跑({@code :plugins:kubejs:runGameTestServer},见插件的 build.gradle),套件 {@code numen_kubejs}。
 */
public class KubeJsGameTests {

    private static final String BATCH = "numen_kubejs";
    private static final String SCRIPT = "numen_gametest.js";

    /** 第一版:一个插件,一组一个函数,一种事件;另一个插件声明到一半抛了。 */
    private static final String FIRST = """
            NumenEvents.register(event => {
              event.plugin('gt_script', plugin => {
                plugin.eventType('gt_script_ping', false)
                plugin.group('say', 'Test fixture: things she can say.', group => {
                  group.fn('hello', fn => fn
                    .summary('Greet someone by name.')
                    .param('who', 'string', 'The name.')
                    .returns('string')
                    .example('gt_script.say.hello("Alex")')
                    .run(call => 'hello ' + call.arg('who')))
                })
              })
              event.plugin('gt_script_broken', plugin => {
                throw new Error('this plugin cannot be set up')
              })
            })
            """;

    /** 第二版:同一个插件换了一个函数(hello 没了),没有事件了;坏的那个也没了。 */
    private static final String SECOND = """
            NumenEvents.register(event => {
              event.plugin('gt_script', plugin => {
                plugin.group('say', 'Test fixture: things she can say.', group => {
                  group.fn('shout', fn => fn
                    .summary('Greet someone by name, loudly.')
                    .param('who', 'string', 'The name.')
                    .option('times', 'integer', 'How many times.', 'shout once')
                    .returns('string')
                    .example('gt_script.say.shout("Alex")')
                    .run(call => {
                      const times = call.option('times') == null ? 1 : call.option('times')
                      return ('HELLO ' + call.arg('who').toUpperCase() + ' ').repeat(times).trim()
                    }))
                })
              })
            })
            """;

    @BeforeBatch(batch = BATCH)
    public static void prepareKubejsBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    private static void script(String text) {
        try {
            Path file = KubeJSPaths.SERVER_SCRIPTS.resolve(SCRIPT);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void unscript() {
        try {
            Files.deleteIfExists(KubeJSPaths.SERVER_SCRIPTS.resolve(SCRIPT));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 管理员的 {@code /reload}:数据包连同 KubeJS 的服务端脚本一起重载,异步,所以用例轮询它的结果。 */
    private static void reload(MinecraftServer server) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
    }

    private static PluginRoster.Plugin row(String id) {
        return NumenPlugins.roster().plugins().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void a_script_declares_a_plugin_and_a_reload_replaces_it(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        NumenPlayer her = spawnAt(helper, "gametest_kubejs_user", new BlockPos(3, 2, 3), false);
        AtomicReference<ToolRun> run = new AtomicReference<>();
        script(FIRST);
        reload(server);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(ApiRegistry.function("gt_script.say.hello") != null,
                        "the script's plugin was not registered after /reload"))
                .thenExecute(() -> run.set(lua(her, "return gt_script.say.hello('Alex')")))
                .thenWaitUntil(() -> helper.assertTrue(run.get().succeeded()
                                && "hello Alex".equals(valueIn(run.get().reply()).getAsString()),
                        "she could not call the script's function: " + run.get().outcome()))
                .thenExecute(() -> {
                    var plugin = row("gt_script");
                    helper.assertTrue(plugin != null && plugin.source() == PluginRoster.Source.SCRIPT
                                    && plugin.status() == PluginRoster.Status.LOADED,
                            "the script plugin is not on the roster as loaded from a script: " + plugin);
                    var broken = row("gt_script_broken");
                    helper.assertTrue(broken != null && broken.status() == PluginRoster.Status.FAILED
                                    && broken.reason().contains("cannot be set up"),
                            "the plugin that threw is not marked failed with its reason: " + broken);
                    helper.assertTrue(com.dwinovo.numen.api.agent.inbox.EventTypes.isRegistered("gt_script_ping"),
                            "the script's event type was not registered");
                    script(SECOND);
                    reload(server);
                })
                .thenWaitUntil(() -> helper.assertTrue(ApiRegistry.function("gt_script.say.shout") != null,
                        "the new script's function did not appear after /reload"))
                .thenExecute(() -> run.set(lua(her, "return gt_script.say.shout('Alex', {times = 2})")))
                .thenWaitUntil(() -> helper.assertTrue(run.get().succeeded()
                                && "HELLO ALEX HELLO ALEX".equals(valueIn(run.get().reply()).getAsString()),
                        "she could not call the new function: " + run.get().outcome()))
                .thenExecute(() -> run.set(lua(her, "return gt_script.say.hello('Alex')")))
                .thenWaitUntil(() -> helper.assertTrue(!run.get().succeeded() && "no_function".equals(run.get().kind()),
                        "the old function is still there after the script changed: " + run.get().outcome()))
                .thenExecute(() -> {
                    helper.assertTrue(ApiRegistry.function("gt_script.say.hello") == null, "the old function stayed registered");
                    helper.assertTrue(!com.dwinovo.numen.api.agent.inbox.EventTypes.isRegistered("gt_script_ping"),
                            "the old script's event type stayed registered");
                    helper.assertTrue(row("gt_script_broken") == null, "the plugin of the old script stayed on the roster");
                    unscript();
                    reload(server);
                })
                .thenWaitUntil(() -> helper.assertTrue(row("gt_script") == null && ApiRegistry.function("gt_script.say.shout") == null,
                        "deleting the script did not withdraw its plugin"))
                .thenExecute(() -> CompanionFactory.despawn(server, her))
                .thenSucceed();
    }
}
