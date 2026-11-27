package com.dwinovo.numen.plugins.minecraft.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景的公共件:一台共用的服务端(规格里的地址)、一个共用的假模型、每个场景自己的玩家。玩家有两种:原版玩家({@link Bot},没装 Numen),
 * 和无头的主人客户端({@link HeadlessOwner},她的大脑在它上面,调的是假模型)。
 * 场景之间靠不同的玩家名与同伴名隔开,收场时把自己召的同伴遣散、玩家退出。
 */
abstract class Scenario {

    static final Spec SPEC = Spec.fromProperties();
    static final FakeModel MODEL = startModel();
    /** 等的上限:起了真模型线程与大脑,慢的是第一次。 */
    static final Duration WAIT = Duration.ofSeconds(30);

    private final List<Bot> bots = new ArrayList<>();
    private final List<HeadlessOwner> owners = new ArrayList<>();
    private final List<Companion> companions = new ArrayList<>();

    private record Companion(Bot owner, String name) {}

    private static FakeModel startModel() {
        try {
            FakeModel model = new FakeModel("127.0.0.1", SPEC.modelPort(), SPEC.modelKey());
            Runtime.getRuntime().addShutdownHook(new Thread(model::close));
            return model;
        } catch (IOException e) {
            throw new IllegalStateException("假模型起不来(端口 " + SPEC.modelPort() + "):" + e, e);
        }
    }

    @BeforeEach
    void freshModel() {
        MODEL.reset();
    }

    @AfterEach
    void leave() throws Exception {
        for (Companion companion : companions) {
            companion.owner.command("numen player delete " + companion.name);
        }
        // 遣散不一定当场落地:等她真离开玩家列表,下一个场景才能用同一个名字
        for (Companion companion : companions) {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (console("list").contains(companion.name)) {
                assertTrue(System.nanoTime() < deadline, companion.name + " 遣散之后还在玩家列表里");
                Thread.sleep(100);
            }
        }
        bots.forEach(Bot::close);
        owners.forEach(HeadlessOwner::close);
        for (Bot bot : bots) {
            assertTrue(bot.packetErrors().isEmpty(), bot.name() + " 收到了读不通的包: " + bot.packetErrors());
        }
    }

    /** 以这个名字和客户端语言进服。 */
    Bot join(String name, String locale) throws Exception {
        Bot bot = Bot.join(SPEC, name, locale);
        bots.add(bot);
        return bot;
    }

    /** 以这个名字进服,装着 Numen 的无头主人客户端:她们的大脑坐在它上面,模型是假模型。 */
    HeadlessOwner joinOwner(String name, String locale) throws Exception {
        HeadlessOwner owner = HeadlessOwner.join(SPEC, MODEL, name, locale);
        owners.add(owner);
        bots.add(owner.bot());
        return owner;
    }

    /** 原版玩家召唤她(没有大脑坐她的座位,她只是一个玩家),等服务端回话召唤成了。 */
    void summon(Bot owner, String her) {
        int mark = owner.mark();
        // 先记下再等回话:等不到回话的场景收场时也要把她遣散,否则同名的她留在服务端,拖累后面的场景
        companions.add(new Companion(owner, her));
        owner.command("numen player summon " + her);
        // 装了 Numen 的客户端收到的是带语言键的话(它自己翻),原版客户端收到的是服务端翻好的
        owner.await(mark, m -> "numen.command.summoned".equals(Text.key(m.content())) && Text.arguments(m.content()).contains(her)
                || m.text().contains(her) && (m.text().contains("Summoned") || m.text().contains("召唤了")), WAIT, "召唤的回话");
    }

    /** 主人召唤她,等她的大脑在主人的客户端上坐上座位;返回她的 UUID。 */
    UUID summon(HeadlessOwner owner, String her) {
        summon(owner.bot(), her);
        UUID uuid = owner.awaitCompanion(her);
        owner.awaitSeated(uuid);
        return uuid;
    }

    /** 在服务端控制台敲一条指令(不带斜杠),返回回话。 */
    static String console(String command) throws IOException {
        try (Rcon rcon = new Rcon(SPEC.host(), SPEC.rconPort(), SPEC.rconPassword())) {
            return rcon.command(command);
        }
    }
}
