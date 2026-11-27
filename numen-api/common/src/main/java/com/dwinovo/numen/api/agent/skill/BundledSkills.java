package com.dwinovo.numen.api.agent.skill;

import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Shipped;
import com.dwinovo.numen.api.plugin.ClientMods;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 随模组与插件发布的技能,在服务端:读登记过的来源({@link Shipped#bundles})里的 {@code skills/} 目录。主人的客户端从目录里知道
 * 有哪几份(名字与说明),正文与附属文件要用时才向这里取({@code skill/read})。目标模组只在客户端的联动带的技能,她的主人的客户端
 * 没报它就不在其中({@link ClientMods}),和别的内置内容同一个判据。
 *
 * <p>只读;线程:任何线程。
 */
// TODO 技能改从 Numen 官网下载后删掉这个类:插件与模组不再随 jar 带技能
public final class BundledSkills {

    private BundledSkills() {}

    /** 一份技能在发布来源里的位置。 */
    private record Located(EntryId id, Path dir, SkillInfo info) {}

    /** 她能用的内置技能:有说明的(模型对一份什么都不知道的技能挑不出来)、她的主人的客户端提供的,按名字空间再按名字排。 */
    public static List<SkillInfo> list(UUID companion) {
        List<SkillInfo> out = new ArrayList<>();
        for (Located skill : all()) {
            if (ClientMods.offered(companion, skill.id().namespace()) && skill.info().description() != null
                    && !skill.info().description().isBlank()) {
                out.add(skill.info());
            }
        }
        return out;
    }

    /**
     * 一份技能的正文。
     *
     * @throws IllegalArgumentException 没有这一份(或她的主人的客户端不提供它)
     */
    public static SkillInfo get(UUID companion, String name) {
        return located(companion, name).info();
    }

    /**
     * 技能目录里的一个附属文件;路径规范化后必须仍在技能目录内。
     *
     * @throws IllegalArgumentException 没有这份技能、路径越界、文件缺失或超限
     */
    public static String file(UUID companion, String name, String relPath) {
        return SkillFiles.read(located(companion, name).dir(), name, relPath);
    }

    /** 技能目录里能引用的附属文件的相对路径清单。 */
    public static List<String> files(UUID companion, String name) {
        return SkillFiles.list(located(companion, name).dir());
    }

    private static Located located(UUID companion, String name) {
        for (Located skill : all()) {
            if (skill.info().name().equals(name) && ClientMods.offered(companion, skill.id().namespace())) {
                return skill;
            }
        }
        throw new IllegalArgumentException("unknown skill: " + name);
    }

    private static List<Located> all() {
        List<Located> out = new ArrayList<>();
        for (Shipped.Bundle bundle : Shipped.bundles()) {
            Path skills = bundle.root().resolve(SkillRegistry.KIND.dir());
            if (!Files.isDirectory(skills)) {
                continue;
            }
            try (Stream<Path> spaces = Files.list(skills)) {
                for (Path space : spaces.filter(Files::isDirectory).sorted().toList()) {
                    try (Stream<Path> dirs = Files.list(space)) {
                        for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                            SkillInfo info = Files.isRegularFile(dir.resolve(SkillRegistry.SKILL_FILENAME))
                                    ? SkillRegistry.read(dir) : null;
                            if (info != null) {
                                out.add(new Located(new EntryId(space.getFileName().toString(),
                                        dir.getFileName().toString()), dir, info));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read the skills of " + bundle.source(), e);
            }
        }
        return out;
    }
}
