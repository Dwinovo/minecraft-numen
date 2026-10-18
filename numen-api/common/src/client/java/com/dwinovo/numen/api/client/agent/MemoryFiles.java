package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.client.chat.ChatLines;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.payload.ExportPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务端导出来的她的记忆(zip)在这台电脑上存成文件:弹系统的保存对话框(独立线程,别冻住主循环),选好了写盘,
 * 结果回到聊天栏说一句。主人取消对话框就什么也不写。
 */
final class MemoryFiles {

    private MemoryFiles() {}

    static void save(ExportPayload export) {
        String suggested = export.companionName() + "-memory.zip";
        new Thread(() -> {
            String chosen = null;
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.zip")).flip();
                chosen = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_saveFileDialog(
                        I18n.get(Keys.EXPORT_PICKER_TITLE), suggested, filters, "ZIP");
            } catch (Throwable t) {
                Constants.LOG.warn("[numen-export] native file dialog unavailable", t);
            }
            String path = chosen;
            Minecraft.getInstance().execute(() -> {
                if (path != null) {
                    write(export, Path.of(path));
                }
            });
        }, "numen-export-picker").start();
    }

    private static void write(ExportPayload export, Path file) {
        try {
            Files.write(file, export.zip());
            ChatLines.notice(export.companionName(), I18n.get(Keys.EXPORT_SAVED, file.toString()));
        } catch (IOException e) {
            Constants.LOG.warn("[numen-export] 写不了 {}: {}", file, e.toString());
            ChatLines.notice(export.companionName(), I18n.get(Keys.EXPORT_FAILED, export.companionName()));
        }
    }
}
