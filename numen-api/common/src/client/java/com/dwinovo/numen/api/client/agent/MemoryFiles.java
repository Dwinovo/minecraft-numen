package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.client.chat.ChatLines;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 她的记忆导出到这台电脑上:先弹系统的保存对话框(独立线程,别冻住主循环),主人选定了位置才向她的大脑要这一份 zip(在她的写车道上读,
 * 刚说的话也在里面),拿到了在后台线程写盘,结果回聊天栏说一句。主人取消对话框就什么也不要。
 *
 * <p>线程:客户端主线程(对话框与写盘在各自的后台线程里,结果回主线程)。
 */
public final class MemoryFiles {

    private MemoryFiles() {}

    /** 点了"导出":问存哪儿,选定了才向她的大脑要这一份。 */
    public static void ask(UUID companion, String name) {
        String suggested = name + "-memory.zip";
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
                    ClientCompanions.getOrCreate(companion).requestExport().whenComplete((zip, failure) -> {
                        if (failure != null) {
                            Minecraft.getInstance().execute(() -> failed(name));
                            return;
                        }
                        save(Path.of(path), zip, name);
                    });
                }
            });
        }, "numen-export-picker").start();
    }

    /** 在后台线程写盘,结果回主线程说一句。 */
    private static void save(Path file, byte[] zip, String name) {
        IOException error = null;
        try {
            Files.write(file, zip);
        } catch (IOException e) {
            error = e;
        }
        IOException failure = error;
        Minecraft.getInstance().execute(() -> {
            if (failure == null) {
                ChatLines.notice(name, I18n.get(Keys.EXPORT_SAVED, file.toString()));
            } else {
                Constants.LOG.warn("[numen-export] cannot write {}: {}", file, failure.toString());
                failed(name);
            }
        });
    }

    private static void failed(String name) {
        ChatLines.notice(name, I18n.get(Keys.EXPORT_FAILED, name));
    }
}
