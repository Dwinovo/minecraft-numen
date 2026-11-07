package com.dwinovo.numen.api.agent.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 手动更新随包快照:从 models.dev 拉最新数据,裁剪后写到指定文件。只由 Gradle 任务
 * {@code :ai:updateModelsSnapshot} 调用,普通构建不联网。
 *
 * <p>用法:{@code SnapshotUpdate <输出文件> [代理 host:port]}
 */
public final class SnapshotUpdate {

    private SnapshotUpdate() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        String proxy = args.length > 1 ? args[1] : "";
        String slim = ModelsDevDownload.slimmed(ModelsDevDownload.API, proxy).join();
        Files.writeString(out, slim, StandardCharsets.UTF_8);
        System.out.println("wrote " + out + " (" + slim.length() + " chars, "
                + ModelsDev.parse(slim).size() + " providers)");
    }
}
