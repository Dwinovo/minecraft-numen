package com.dwinovo.numen.client;

import com.dwinovo.numen.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 把配置从 {@code config/numen_api/} 搬到 {@code config/numen/}——一次性,搬完即无事。
 *
 * <h2>为什么会有两个根</h2>
 * 早先用 {@link Constants#MOD_ID} 当配置根目录名,而那是引擎 jar 在加载器眼里的身份
 * ({@code numen_api}),不是玩家眼里的产品名。于是技能和 mcp_clients.json 落进了
 * {@code config/numen_api/},而同伴、人设、皮肤落进 {@code config/numen/}——同一份配置
 * 一分为二。界面文案和文档说的一直是后者,所以玩家照提示往 {@code config/numen/skills}
 * 放的技能<b>根本不会被加载</b>(issue #66)。
 *
 * <p>根已经收到 {@link Constants#CONFIG_ROOT} 一处。这里负责把旧位置的东西接过来,
 * 免得升级之后玩家的 MCP 配置凭空消失。
 */
public final class ConfigRootMigration {

    /** 旧根下值得搬的东西。技能列在这里是因为有人从日志里发现了真实路径、照着放了。 */
    private static final List<String> ITEMS = List.of("mcp_clients.json", "skills");

    private ConfigRootMigration() {}

    /**
     * @param configDir 加载器给的 {@code config/} 目录
     */
    public static void run(Path configDir) {
        if (configDir == null) return;
        Path legacy = configDir.resolve(Constants.MOD_ID);
        if (!Files.isDirectory(legacy)) return;                 // 没有旧根,正常情况
        Path current = configDir.resolve(Constants.CONFIG_ROOT);

        int moved = 0;
        for (String name : ITEMS) {
            Path from = legacy.resolve(name);
            Path to = current.resolve(name);
            if (!Files.exists(from) || Files.exists(to)) continue;  // 新位置已有就不覆盖
            try {
                Files.createDirectories(current);
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                moved++;
            } catch (IOException e) {
                Constants.LOG.warn("[numen] 迁移 {} 失败,原件还在 {}", name, from, e);
            }
        }
        if (moved > 0) {
            Constants.LOG.info("[numen] 已把 {} 项配置从 config/{}/ 搬到 config/{}/",
                    moved, Constants.MOD_ID, Constants.CONFIG_ROOT);
        }
        // 旧根空了就删掉,别留一个让人以为还有用的空壳
        try (var s = Files.list(legacy)) {
            if (s.findAny().isEmpty()) Files.delete(legacy);
        } catch (IOException ignored) {
        }
    }
}
