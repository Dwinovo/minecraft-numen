package com.dwinovo.numen.api.agent.skill;

import com.dwinovo.numen.api.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 一份技能目录里的附属文件:三级披露的第三级(清单 → 正文 → 附属文件)。她自己的技能(主人客户端上)与随模组发布的技能(服务端上)
 * 读的是同一种目录,所以怎么读、什么算越界、多大算太大只在这里一处。
 */
public final class SkillFiles {

    /** 附属文件尺寸上限(角色是"给模型看的文本",不是数据仓)。 */
    public static final long MAX_BYTES = 256 * 1024;

    private SkillFiles() {}

    /**
     * 读技能目录内的一个附属文件。路径规范化后必须仍在技能目录内——拒绝任何形式的穿越。
     *
     * @param skill 技能名,只用于说明
     * @throws IllegalArgumentException 路径越界、文件缺失或超限
     */
    public static String read(Path dir, String skill, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("file is required");
        }
        Path target = dir.resolve(relPath).normalize();
        if (!target.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("file must stay inside the skill directory");
        }
        try {
            if (!Files.isRegularFile(target)) {
                throw new IllegalArgumentException("no such file in skill " + skill + ": " + relPath
                        + "; available: " + String.join(", ", list(dir)));
            }
            if (Files.size(target) > MAX_BYTES) {
                throw new IllegalArgumentException(relPath + " exceeds the " + (MAX_BYTES / 1024) + "KB support-file cap");
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + relPath + ": " + e.getMessage(), e);
        }
    }

    /** 技能目录内可引用的附属文件相对路径清单(不含 SKILL.md 本身,字典序)。 */
    public static List<String> list(Path dir) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().equals(SkillRegistry.SKILL_FILENAME))
                    .forEach(f -> out.add(dir.relativize(f).toString().replace(java.io.File.separatorChar, '/')));
        } catch (IOException e) {
            Constants.LOG.warn("[numen-skill] cannot list support files in {}", dir, e);
        }
        out.sort(String::compareTo);
        return out;
    }
}
