package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.prompt.NumenPrompts;
import com.dwinovo.numen.cli.CommandTool;
import com.dwinovo.numen.cli.WrittenCommands;
import com.dwinovo.numen.core.build.Design;
import com.dwinovo.numen.task.reflex.ReflexRegistry;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写着的命令不走样:技能文档、系统提示、每个工具与动作的说明里写着的每一行命令,以及随模组发的设计文件,都按命令树读一遍,
 * 读不通就指出在哪一处、哪一行、为什么。判据只有命令树——第 1 层是 Numen 自己的树({@link WrittenCommands}),第 0 层是
 * 原版的指令树(按 OP 4 级读,看得见每一条)——不另记一份"有哪些命令"。
 *
 * <p>怎么认出一行命令见 {@link WrittenCommands}:反引号或代码块里、以一级命令或 {@code /} 打头的那些。
 * 插件的技能文档要它自己的模组在场才登记得上它的命令组,不在这里读。
 */
class WrittenCommandsTest {

    private static WrittenCommands.NativeReader vanilla;

    @BeforeAll
    static void install() {
        CoreCommandsFixture.install();
        CommandDispatcher<CommandSourceStack> dispatcher = new Commands(Commands.CommandSelection.ALL,
                Commands.createValidationContext(VanillaRegistries.createLookup())).getDispatcher();
        CommandSourceStack op = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 4, "lint",
                Component.literal("lint"), null, null);
        vanilla = line -> WrittenCommands.nativeProblem(dispatcher, line, op);
    }

    private static void assertReads(List<WrittenCommands.Text> texts, int atLeast) {
        List<WrittenCommands.Wrong> wrong = WrittenCommands.check(texts, vanilla);
        int lines = texts.stream().mapToInt(t -> WrittenCommands.in(t.body()).size()).sum();
        assertTrue(wrong.isEmpty(), wrong.size() + " written command(s) do not read:\n"
                + String.join("\n", wrong.stream().map(WrittenCommands.Wrong::toString).toList()));
        assertTrue(lines >= atLeast, "only " + lines + " command line(s) found — the convention is not being followed");
    }

    /** core 随身带的技能文档:每一份 SKILL.md,和它们按需读的参考文件。 */
    @Test
    void everySkillDocumentWritesCommandsThatRead() throws IOException, URISyntaxException {
        Path skills = Path.of(WrittenCommandsTest.class.getClassLoader()
                .getResource("skills/building_design/SKILL.md").toURI()).getParent().getParent();
        List<WrittenCommands.Text> texts = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(skills)) {
            for (Path doc : walk.filter(p -> p.toString().endsWith(".md")).toList()) {
                texts.add(new WrittenCommands.Text(skills.relativize(doc).toString(), Files.readString(doc)));
            }
        }
        assertReads(texts, 40);
    }

    /** 系统提示:操作纪律、札记与说话的规矩、本能名册。 */
    @Test
    void theSystemPromptWritesCommandsThatRead() {
        assertReads(List.of(
                new WrittenCommands.Text("NumenPrompts.ENTITY_PROMPT", NumenPrompts.ENTITY_PROMPT),
                new WrittenCommands.Text("NumenPrompts.MEMORY", NumenPrompts.MEMORY),
                new WrittenCommands.Text("NumenPrompts.CONVERSATION", NumenPrompts.CONVERSATION),
                new WrittenCommands.Text("NumenPrompts.SPEAKING", NumenPrompts.SPEAKING),
                new WrittenCommands.Text("instincts", ReflexRegistry.overview())), 10);
    }

    /** 每个命令组与动作的说明、参数说明、例子与注意,工具表里每个工具的描述与参数说明。 */
    @Test
    void everyActionAndToolDescriptionWritesCommandsThatRead() {
        List<WrittenCommands.Text> texts = new ArrayList<>(WrittenCommands.registered());
        texts.addAll(WrittenCommands.toolTexts(new CommandTool()));
        assertReads(texts, 150);
    }

    /** 随模组发的设计文件({@code .numen}):每一步和手写进设计库的一样,按设计的读法读(就是这棵命令树)。 */
    @Test
    void everyBundledDesignReads() throws IOException, URISyntaxException {
        Path resources = Path.of(WrittenCommandsTest.class.getClassLoader()
                .getResource("skills/building_design/SKILL.md").toURI()).getParent().getParent().getParent();
        try (Stream<Path> walk = Files.walk(resources)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".numen")).toList()) {
                String name = file.getFileName().toString().replace(".numen", "");
                Design.parse(name, Files.readString(file));
            }
        }
    }
}
