package com.dwinovo.numen.api.client.skin;

import com.dwinovo.numen.api.Constants;
import net.minecraft.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 在这台机器的系统文件管理器里打开一个文件夹(原版的 {@link Util.OS#openFile})。Windows 与 macOS 一定有;Linux 靠 {@code xdg-open},
 * 手机上的启动器(FCL 等)没有它——那里打不开,界面改成显示路径,请玩家用手机自带的文件管理器把图放进去(FCL 会把游戏目录通过
 * FolderProvider 交给手机的文件管理器)。
 */
public final class FolderOpener {

    private FolderOpener() {}

    /** 这个平台上能不能替玩家打开文件夹。 */
    public static boolean available() {
        boolean needsOpener = Util.getPlatform() == Util.OS.LINUX || Util.getPlatform() == Util.OS.SOLARIS;
        return !needsOpener || onPath("xdg-open");
    }

    /** 打开(文件夹还没有就先建出来)。 */
    public static void open(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Constants.LOG.warn("[numen-skin] 建不了皮肤文件夹 {}: {}", dir, e.toString());
            return;
        }
        Util.getPlatform().openFile(dir.toFile());
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (!entry.isEmpty() && Files.isExecutable(Path.of(entry, program))) {
                return true;
            }
        }
        return false;
    }
}
