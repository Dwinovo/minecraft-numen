package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.prompt.NumenPrompts;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.script.Modules;
import com.dwinovo.numen.sdk.ApiDocs;
import com.dwinovo.numen.sdk.ApiTester;
import com.dwinovo.numen.task.reflex.ReflexRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * core 的 lint 报告,不是守卫:登记的每个函数与随模组发的模块的写法({@link ApiTester#lint()}),技能文档、系统提示、工具说明里写着的
 * 调用({@link ApiTester#lint(List)})。报告写进 {@code build/reports/api-lint.txt} 并打印;它不让构建失败——写法好不好由评测的分数说话,
 * 读这份报告的是写 API 与技能的人;整份 API 的 LuaLS 存根与元数据挨着它写出。插件的报告经同样的 {@link #documents} 与 {@link #write}。
 */
public class ApiLintReport {

    @Test
    void writeTheReport() throws IOException, URISyntaxException {
        CoreApiFixture.install();
        List<ApiTester.Lint> lint = new ArrayList<>(ApiTester.lint());
        List<ApiTester.Text> texts = new ArrayList<>(documents("skills"));
        texts.add(new ApiTester.Text("NumenPrompts.ENTITY_PROMPT", NumenPrompts.ENTITY_PROMPT));
        texts.add(new ApiTester.Text("NumenPrompts.MEMORY", NumenPrompts.MEMORY));
        texts.add(new ApiTester.Text("NumenPrompts.CONVERSATION", NumenPrompts.CONVERSATION));
        texts.add(new ApiTester.Text("NumenPrompts.SPEAKING", NumenPrompts.SPEAKING));
        texts.add(new ApiTester.Text("instincts", ReflexRegistry.overview()));
        ToolRegistry.all().forEach(tool -> texts.add(new ApiTester.Text("tool " + tool.name(), tool.description())));
        lint.addAll(ApiTester.lint(texts));
        write(lint);
        // 整份 API 的 LuaLS 存根与机器可读的元数据挨着报告写出,写程序、写技能时放进编辑器的工作区
        Files.writeString(Path.of("build", "reports", "numen-api.lua"), ApiDocs.stubs(Modules.factory()));
        Files.writeString(Path.of("build", "reports", "numen-api.json"), ApiDocs.metadata());
    }

    /** 报告写进 {@code build/reports/api-lint.txt},也打印出来。 */
    public static void write(List<ApiTester.Lint> lint) throws IOException {
        String report = lint.isEmpty() ? "no lint" : String.join("\n", lint.stream().map(ApiTester.Lint::toString)
                .toList());
        Path out = Path.of("build", "reports", "api-lint.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report + "\n");
        System.out.println("[api-lint] " + lint.size() + " finding(s), written to " + out.toAbsolutePath()
                + (lint.isEmpty() ? "" : ":\n" + report));
    }

    /** 类路径上一个目录里的每一份 Markdown(技能正文与它按需读的参考文件)。 */
    public static List<ApiTester.Text> documents(String dir) throws IOException, URISyntaxException {
        URL root = ApiLintReport.class.getClassLoader().getResource(dir);
        assertNotNull(root, dir + " is not on the classpath");
        Path base = Path.of(root.toURI());
        List<ApiTester.Text> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(base)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                out.add(new ApiTester.Text(base.relativize(file).toString(), Files.readString(file)));
            }
        }
        return out;
    }
}
