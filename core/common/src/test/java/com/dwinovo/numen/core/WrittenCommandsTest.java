package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.prompt.NumenPrompts;
import com.dwinovo.numen.cli.CommandTool;
import com.dwinovo.numen.cli.WrittenCommands;
import com.dwinovo.numen.core.build.Design;
import com.dwinovo.numen.task.reflex.ReflexRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.dwinovo.numen.core.WrittenCommandsLint.assertReads;

/**
 * 写着的命令不走样:技能文档、系统提示、每个工具与动作的说明里写着的每一行命令,以及随模组发的设计文件,都按命令树读一遍,
 * 读不通就指出在哪一处、哪一行、为什么。读法在 {@link WrittenCommandsLint}:第 1 层是 Numen 自己的树({@link WrittenCommands}),
 * 第 0 层是原版的指令树——不另记一份"有哪些命令"。
 *
 * <p>怎么认出一行命令见 {@link WrittenCommands}:反引号或代码块里、以一级命令或 {@code /} 打头的那些。插件的技能与说明由
 * 各插件模块自己的防漂移测试读(同一个 {@link WrittenCommandsLint}),它们的命令组只进那个插件的测试进程。
 */
class WrittenCommandsTest {

    @BeforeAll
    static void install() {
        WrittenCommandsLint.install();
    }

    /** core 随身带的技能文档:每一份 SKILL.md,和它们按需读的参考文件。 */
    @Test
    void everySkillDocumentWritesCommandsThatRead() throws IOException, URISyntaxException {
        assertReads(WrittenCommandsLint.documents("skills"), 40);
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
