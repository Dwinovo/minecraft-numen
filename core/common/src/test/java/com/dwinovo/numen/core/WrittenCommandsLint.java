package com.dwinovo.numen.core;

import com.dwinovo.numen.cli.WrittenCommands;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防漂移测试共用的一套读法:装上 core 的命令树({@link CoreCommandsFixture})与原版的指令树(按 OP 4 级读,看得见每一条),
 * 把一批文字里写着的每一行命令读一遍,读不通就指出在哪一处、哪一行、为什么。core 自己的({@code WrittenCommandsTest})
 * 与各个插件的防漂移测试都经这里,判据只有命令树这一处({@link WrittenCommands})。
 *
 * <p>插件的测试在它自己模块的测试进程里跑:命令树是进程级的静态表,插件的组只进它自己那一个进程,不混进 core 的单测。
 */
public final class WrittenCommandsLint {

    private static WrittenCommands.NativeReader vanilla;

    private WrittenCommandsLint() {}

    /** 装上 core 的命令组与原版的指令树;一个进程只装一次。 */
    public static synchronized void install() {
        if (vanilla != null) {
            return;
        }
        CoreCommandsFixture.install();
        CommandDispatcher<CommandSourceStack> dispatcher = new Commands(Commands.CommandSelection.ALL,
                Commands.createValidationContext(VanillaRegistries.createLookup())).getDispatcher();
        CommandSourceStack op = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 4, "lint",
                Component.literal("lint"), null, null);
        vanilla = line -> WrittenCommands.nativeProblem(dispatcher, line, op);
    }

    /**
     * 这些文字里写着的命令都读得通,而且一共至少 {@code atLeast} 行——少于它说明约定(写进反引号)没被遵守,读到的太少。
     */
    public static void assertReads(List<WrittenCommands.Text> texts, int atLeast) {
        install();
        List<WrittenCommands.Wrong> wrong = WrittenCommands.check(texts, vanilla);
        int lines = texts.stream().mapToInt(t -> WrittenCommands.in(t.body()).size()).sum();
        assertTrue(wrong.isEmpty(), wrong.size() + " written command(s) do not read:\n"
                + String.join("\n", wrong.stream().map(WrittenCommands.Wrong::toString).toList()));
        assertTrue(lines >= atLeast, "only " + lines + " command line(s) found — the convention is not being followed");
    }

    /** 类路径上这个资源目录(技能库的根)下的每一份 {@code .md},出处写成相对它的路径。 */
    public static List<WrittenCommands.Text> documents(String resourceDir) throws IOException, URISyntaxException {
        URL url = WrittenCommandsLint.class.getClassLoader().getResource(resourceDir);
        assertNotNull(url, "no " + resourceDir + " on the test classpath");
        Path root = Path.of(url.toURI());
        List<WrittenCommands.Text> texts = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path doc : walk.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                texts.add(new WrittenCommands.Text(resourceDir + "/" + root.relativize(doc), Files.readString(doc)));
            }
        }
        return texts;
    }

    /** 登记在册的说明文字里,属于这一个命令组的那些:组的一句话,它每个动作的说明、参数、例子与注意。 */
    public static List<WrittenCommands.Text> registeredUnder(String group) {
        install();
        return WrittenCommands.registered().stream()
                .filter(t -> t.where().equals(group) || t.where().startsWith(group + " "))
                .toList();
    }
}
