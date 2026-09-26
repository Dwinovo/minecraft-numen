package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 以谁的权威执行:只有两种,默认她自己的;借服务器权威的动作要在声明里写明,帮助里也写明,处理函数只有这时才拿得到
 * {@link OnHer},而它写出的每一行,目标都是她。真服务器上以服务器的权威执行、读回补全,在 GameTest 与真机里验。
 */
class AuthorityTest {

    /** 处理函数拿 {@link ServerSource#onHer()} 的结果:拿到了是 null,没拿到是抛出的那句话。 */
    static final AtomicReference<String> REFUSED = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_authority", "A wrapper and a plain action.", g -> {
            g.server("wrap", "Wrap a native admin command.", AuthorityTest::borrow)
                    .authority(Authority.SERVER_ON_HER)
                    .example("gt_authority wrap");
            g.server("plain", "Act with her own authority.", AuthorityTest::borrow)
                    .example("gt_authority plain");
        });
    }

    private static void borrow(ServerSource src, CommandArgs args) {
        try {
            src.onHer();
            REFUSED.set(null);
        } catch (IllegalStateException e) {
            REFUSED.set(e.getMessage());
        }
        src.reply(TaskResult.ok("done").toJson());
    }

    @Test
    void onlyADeclaredActionGetsTheServersAuthority() {
        REFUSED.set("not run");
        assertTrue(onServer("gt_authority wrap").success());
        assertEquals(null, REFUSED.get(), "声明了的动作拿得到");

        assertTrue(onServer("gt_authority plain").success());
        assertEquals("gt_authority plain runs with her own authority; declare "
                + "authority(Authority.SERVER_ON_HER) to borrow the server's", REFUSED.get(), "没声明的拿不到");
    }

    /** 作用对象写死为她:调用方只给目标前后的那两截,她的名字由这里写进去,名字与值需要时加引号。 */
    @Test
    void theTargetIsAlwaysHer() {
        assertEquals("ysm model set \"小焰\" \"misc/1_alex\" default",
                OnHer.line("小焰", "ysm model set", "misc/1_alex", "default"));
        assertEquals("ysm play Aria stop", OnHer.line("Aria", "ysm play", "stop"));
        assertEquals("ysm auth \"Aria Two\" clear", OnHer.line("Aria Two", "ysm auth", "clear"));
        assertEquals("抽象鸣潮 菲比.ysm", OnHer.value("\"抽象鸣潮 菲比.ysm\""), "补全给的带引号的写法还原成值");
        assertEquals("misc/1_alex", OnHer.value("misc/1_alex"));
    }

    @Test
    void theHelpSaysWhoseAuthorityItIs() {
        String wrap = onClient("gt_authority wrap --help").message();
        assertEquals("""
                gt_authority wrap
                  Wrap a native admin command.
                  Runs with the server's authority, and only on you.
                  Examples:
                    gt_authority wrap""", wrap);
        assertFalse(onClient("gt_authority plain --help").message().contains(CommandHelp.SERVER_ON_HER),
                "她自己的是默认,不写");
    }

    @Test
    void theDeclarationHasTwoShapesAndOnlyServerActionsBorrow() {
        assertEquals(2, Authority.values().length);
        assertThrows(IllegalArgumentException.class, () -> door().registerCommands("gt_authority_client", "x.",
                g -> g.client("jot", "Jot.", (src, args) -> { }).authority(Authority.SERVER_ON_HER)
                        .example("gt_authority_client jot")), "客户端动作借不了服务器的权威");
        assertThrows(IllegalArgumentException.class, () -> door().registerCommands("gt_authority_null", "x.",
                g -> g.server("go", "Go.", (src, args) -> { }).authority(null).example("gt_authority_null go")));
        AtomicReference<Action> leaked = new AtomicReference<>();
        door().registerCommands("gt_authority_closed", "x.",
                g -> leaked.set(g.server("go", "Go.", (src, args) -> { }).example("gt_authority_closed go")));
        assertThrows(IllegalStateException.class, () -> leaked.get().authority(Authority.SERVER_ON_HER),
                "封口之后不能再改权威");
    }
}
