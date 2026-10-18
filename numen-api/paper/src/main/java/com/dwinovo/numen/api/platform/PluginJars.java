package com.dwinovo.numen.api.platform;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 插件 jar 里的路径对应的 {@link Path}:把 jar 当 zip 文件系统打开,交给要读整个目录的人(内置内容的同步、语言文件)。
 * 文件系统开着直到 {@link #closeAll()}——同步和读语言表发生在服务器起好之后,不在取路径的那一刻。
 */
public final class PluginJars {

    private static final Map<Path, FileSystem> OPEN = new HashMap<>();

    private PluginJars() {}

    /**
     * @param inJar jar 内路径,如 {@code numen} 或 {@code assets/numen_api/lang/en_us.json}
     * @return 对应的 Path;jar 里没有这条路径就是 {@code null}
     */
    public static synchronized Path find(Plugin plugin, String inJar) {
        Path jar = jarOf(plugin);
        FileSystem fs = OPEN.get(jar);
        if (fs == null) {
            try {
                fs = FileSystems.newFileSystem(jar);
            } catch (IOException e) {
                throw new UncheckedIOException("读不开插件 jar " + jar, e);
            }
            OPEN.put(jar, fs);
        }
        Path found = fs.getPath(inJar);
        return Files.exists(found) ? found : null;
    }

    /** 插件停用时调:放开 jar 的文件句柄(Windows 上开着就换不掉 jar)。 */
    public static synchronized void closeAll() {
        for (Map.Entry<Path, FileSystem> open : OPEN.entrySet()) {
            try {
                open.getValue().close();
            } catch (IOException e) {
                throw new UncheckedIOException("关不掉插件 jar " + open.getKey(), e);
            }
        }
        OPEN.clear();
    }

    private static Path jarOf(Plugin plugin) {
        try {
            return Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("插件 " + plugin.getName() + " 的 jar 位置不是合法的 URI", e);
        }
    }
}
