package com.dwinovo.numen.api.client.skin;

import com.dwinovo.numen.api.Constants;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 在这台机器的系统文件管理器里打开一个文件夹(原版的 {@link Blaze3D#openPath},经 SDL 打开)。Windows 与 macOS 一定有;Linux 靠 {@code xdg-open},
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
        // 已知问题:原版这一代的 openPath 在游戏进程里调 SDL 的打开接口,系统外壳的扩展(网盘的右键菜单、输入法组件)跟着进了游戏进程,
        // 有的机器上点下去整个游戏卡住不回来;原版自己的"打开资源包文件夹"同样。这里跟原版走。
        // 另起系统的打开程序(Windows 上 rundll32 url.dll,FileProtocolHandler)不会把这些带进来。
        Blaze3D.openPath(dir);
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
