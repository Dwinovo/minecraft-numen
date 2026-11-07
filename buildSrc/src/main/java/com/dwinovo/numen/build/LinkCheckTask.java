package com.dwinovo.numen.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** {@link LinkCheck} 的 Gradle 任务:有问题就失败,一行一个问题。 */
public abstract class LinkCheckTask extends DefaultTask {

    /** 要检查的成品(Paper 插件的 jar)。 */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getProduct();

    /** 成品依赖的其余类(依赖库、Paper 的 API、另一个插件的 jar),只用来补全继承链;不是 jar 的(类目录)不读。 */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getLibraries();

    /** 原版服务端(Mojang 名)jar,恰好一个文件。 */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getVanillaServer();

    /** 要对着解析的 Paper 服务端 jar(Mojang 名、打完补丁的那个)。 */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPaperServer();

    @TaskAction
    public void check() {
        List<File> vanilla = getVanillaServer().getFiles().stream().toList();
        if (vanilla.size() != 1) {
            throw new GradleException("原版服务端 jar 应当恰好一个,实际 " + vanilla);
        }
        List<String> problems;
        try {
            problems = LinkCheck.run(
                    getProduct().getFiles().stream().map(File::toPath).toList(),
                    getLibraries().getFiles().stream().filter(f -> f.isFile() && f.getName().endsWith(".jar"))
                            .map(File::toPath).toList(),
                    vanilla.get(0).toPath(),
                    getPaperServer().get().getAsFile().toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!problems.isEmpty()) {
            throw new GradleException("成品在 " + getPaperServer().get().getAsFile().getName() + " 上链接不上("
                    + problems.size() + " 处):\n  " + String.join("\n  ", problems));
        }
        getLogger().lifecycle("链接检查通过:{} 对着 {}", getProduct().getFiles().stream().map(File::getName).toList(),
                getPaperServer().get().getAsFile().getName());
    }
}
