package com.dwinovo.numen.config;

import com.dwinovo.numen.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置目录的迁移台账——所有"老版本落盘格式 → 现行格式"的搬运都住这里,
 * 引导期一次跑完({@code CommonClass.init} 在任何消费方读盘之前调用)。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li><b>每步幂等且自探测</b>:典型形态是"旧文件在、新文件不在才动手",
 *       重复启动重复跑零副作用。不设版本戳文件——探测条件本身就是版本
 *       判据,少一个需要维护的状态。</li>
 *   <li><b>每步独立兜底</b>:单步失败告警后继续跑后面的步骤,绝不阻断游戏
 *       启动——迁移失败的下场是各消费方自己的默认值(如站点注册表退内置
 *       默认),坏于旧数据,好于打不开游戏。</li>
 *   <li><b>宁改名不留双份</b>:同一份数据新旧两个文件并存必然分叉成双真源,
 *       所以搬运用原子改名。玩家回滚老版本时,老文件名会被老版本重新播种
 *       默认值;再升级回来,自动接续改名后的新文件,自定义不丢。</li>
 * </ol>
 */
public final class ConfigMigrations {

    private ConfigMigrations() {}

    /** 全部迁移步骤,按史序执行;加新迁移 = 尾部加一行 + 一个自探测的私有方法。 */
    public static void run(Path numenConfigDir) {
        step("park-dead-site-catalog", () -> parkDeadSiteCatalog(numenConfigDir));
    }

    /**
     * 站点目录退回只读内置（见 {@code ProviderRegistry}），落盘的那份从此是死数据，
     * 挂成 {@code .bak} 移开。
     *
     * <p>涉两个文件：0.1.1 叫 {@code models.json}，0.1.2 开发期改叫
     * {@code providers.json} —— 而后者正是面板站点库（{@code ProviderLibrary}）
     * 的文件名。<b>所以这里认形状不认文件名</b>：只有顶层是 {@code providers}
     * 数组的才是死目录，{@code entries} 形状是玩家的 API Key，碰一下都不行。
     */
    private static void parkDeadSiteCatalog(Path dir) throws IOException {
        park(dir.resolve("models.json"));
        park(dir.resolve("providers.json"));
    }

    private static void park(Path file) throws IOException {
        if (!Files.exists(file) || !isSiteCatalog(file)) return;
        Path bak = file.resolveSibling(file.getFileName() + ".bak");
        if (Files.exists(bak)) {
            Files.delete(file);   // 已经挂过一份,不再覆盖它
        } else {
            Files.move(file, bak);
        }
        Constants.LOG.info("[numen] 站点目录已内置化,移走旧文件 {}", file.getFileName());
    }

    /** 顶层有 {@code providers} 数组 = 旧站点目录。读不动/不是 JSON 一律算不是（宁可不搬）。 */
    private static boolean isSiteCatalog(Path file) {
        try {
            var root = com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return root.isJsonObject() && root.getAsJsonObject().has("providers")
                    && root.getAsJsonObject().get("providers").isJsonArray();
        } catch (Exception e) {
            return false;
        }
    }

    private static void step(String name, ThrowingRunnable body) {
        try {
            body.run();
        } catch (Exception e) {
            Constants.LOG.warn("[numen] config migration '{}' failed — continuing with defaults", name, e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
