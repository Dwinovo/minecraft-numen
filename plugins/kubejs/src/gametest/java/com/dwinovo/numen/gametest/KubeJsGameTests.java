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

    private static final String BATCH_RANGED = "numen_kubejs_ranged";
    private static final String RANGED_SCRIPT = "numen_gametest_ranged.js";

    /** 一根烈焰棒当"枪":命中扫描,总是现成的,射出去就把目标收走。 */
    private static final String BLASTER = """
            NumenEvents.register(event => {
              event.plugin('gt_ranged', plugin => {
                plugin.rangedWeapon('blaster', weapon => weapon
                  .handles(stack => stack.id == 'minecraft:blaze_rod')
                  .readiness((body, stack) => 'ready')
                  .hitscan(30)
                  .standoff(3, 14)
                  .fire((body, stack, target) => {
                    target.kill()
                    return true
                  }))
              })
            })
            """;

    @BeforeBatch(batch = BATCH_RANGED)
    public static void prepareRangedBatch(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, MIDNIGHT);
    }

    /**
     * 脚本里声明的远程武器:没有脚本时一根烈焰棒什么也不是(她挑不到远程武器);脚本声明了、{@code /reload} 之后,攻击任务挑它、站进它
     * 的环、用它的命中扫描瞄准,调脚本写的 fire 把目标放倒;删掉脚本再 {@code /reload},它又不在了。
     */
    @GameTest(template = "floor16", timeoutTicks = 800, batch = BATCH_RANGED)
    public static void a_script_declared_ranged_weapon_is_used_by_the_attack_task(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_kubejs_shooter", new BlockPos(2, 2, 8), false);
        her.getInventory().clearContent();
        her.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BLAZE_ROD));
        var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(11, 2, 8));
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true);
        level.addFreshEntity(zombie);
        helper.assertTrue(com.dwinovo.numen.combat.Loadout.forTarget(her, zombie).ranged() == null,
                "she found a ranged weapon in a blaze rod before any script provided one");
        try {
            Files.writeString(KubeJSPaths.SERVER_SCRIPTS.resolve(RANGED_SCRIPT), BLASTER);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        reload(server);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(com.dwinovo.numen.combat.Loadout.forTarget(her, zombie).ranged() != null,
                        "the script's ranged weapon was not provided after /reload"))
                .thenExecute(() -> lua(her, "numen.fight.attack(" + ref(zombie) + ")"))
                .thenWaitUntil(() -> helper.assertTrue(zombie.isRemoved() || zombie.isDeadOrDying(),
                        "the script's fire never brought the zombie down"))
                .thenExecute(() -> {
                    try {
                        Files.deleteIfExists(KubeJSPaths.SERVER_SCRIPTS.resolve(RANGED_SCRIPT));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    reload(server);
                })
                .thenWaitUntil(() -> helper.assertTrue(com.dwinovo.numen.combat.Loadout.forTarget(her, her).ranged() == null,
                        "the script's ranged weapon stayed after the script was deleted"))
                .thenExecute(() -> CompanionFactory.despawn(server, her))
                .thenSucceed();
    }

    /** 另一批:脚本重载会换掉整批脚本,和上一个用例各占一批,不并行抢 {@code /reload}。 */
    private static final String KNOWLEDGE_BATCH = "numen_kubejs_knowledge";
    private static final String KNOWLEDGE_SCRIPT = "numen_gametest_knowledge.js";

    /** 一个插件登记一个说明来源和一个配方来源。 */
    private static final String KNOWLEDGE = """
            NumenEvents.register(event => {
              event.plugin('gt_pack', plugin => {
                plugin.knowledgeSource('info', source => source
                  .provides('How the pack wants items used.')
                  .run(item => item == 'minecraft:stick' ? ['Feed sticks to the Flux Press.'] : []))
                plugin.knowledgeSource('recipes', source => source
                  .provides('Recipes of the pack machine.')
                  .notProvided('chance')
                  .run(item => item == 'minecraft:stick'
                    ? [{category: 'gt_pack:press', item: 'minecraft:stick', makes: 4, ingredients: ['oak_log'],
                        catalysts: ['gt_pack:flux_press']}]
                    : []))
              })
            })
            """;

    @BeforeBatch(batch = KNOWLEDGE_BATCH)
    public static void prepareKnowledgeBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 整合包作者在脚本里登记知识来源(control 0016):她问 {@code numen.inv.info}、{@code numen.inv.recipes} 时,脚本写的说明与配方和原版的合在
     * 一起,注明出自这个插件;删了脚本、重载,来源撤回。从入口调(她的 Lua 程序、真的 {@code /reload})。
     */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = KNOWLEDGE_BATCH)
    public static void a_script_registers_knowledge_sources_she_reads_through_the_verbs(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        NumenPlayer her = spawnAt(helper, "gametest_kubejs_reader", new BlockPos(3, 2, 3), false);
        AtomicReference<ToolRun> run = new AtomicReference<>();
        try {
            Path file = KubeJSPaths.SERVER_SCRIPTS.resolve(KNOWLEDGE_SCRIPT);
            Files.createDirectories(file.getParent());
            Files.writeString(file, KNOWLEDGE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        reload(server);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(ApiRegistry.function("gt_pack.recipes_source.read") != null,
                        "the script's knowledge sources were not registered after /reload"))
                .thenExecute(() -> run.set(lua(her, """
                        local i = numen.inv.info('minecraft:stick')
                        local r = numen.inv.recipes('minecraft:stick')
                        local made
                        for _, x in ipairs(r.recipes) do
                          if x.category == 'gt_pack:press' then made = x.sources[1] .. ':' .. x.catalysts[1] end
                        end
                        return i.notes[1].source .. '=' .. i.notes[1].text .. '|' .. tostring(made)
                        """)))
                .thenWaitUntil(() -> {
                    helper.assertTrue(run.get().receipt() != null && run.get().ranToTheEnd(),
                            "the verbs failed: " + run.get().receipt());
                    var merged = run.get().data().get("returned");
                    helper.assertTrue(merged != null && merged.isJsonPrimitive()
                                    && "gt_pack=Feed sticks to the Flux Press.|gt_pack:gt_pack:flux_press"
                                            .equals(merged.getAsString()),
                            "the script's sources were not merged into the verbs: " + run.get().receipt());
                })
                .thenExecute(() -> {
                    try {
                        Files.deleteIfExists(KubeJSPaths.SERVER_SCRIPTS.resolve(KNOWLEDGE_SCRIPT));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    reload(server);
                })
                .thenWaitUntil(() -> helper.assertTrue(ApiRegistry.function("gt_pack.recipes_source.read") == null
                                && com.dwinovo.numen.api.knowledge.KnowledgeSources.of(
                                        com.dwinovo.numen.api.knowledge.KnowledgeKind.INFO).stream()
                                .noneMatch(s -> s.id().equals("gt_pack")),
                        "deleting the script did not withdraw its sources"))
                .thenExecute(() -> CompanionFactory.despawn(server, her))
                .thenSucceed();
    }
}
