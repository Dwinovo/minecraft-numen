package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.Exports;
import com.dwinovo.numen.api.client.chat.ChatLines;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.payload.ExportChunkPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 她的记忆导出到这台电脑上:先弹系统的保存对话框(独立线程,别冻住主循环),主人选定了位置才向服务端要;服务端的 zip 按段依次
 * 下行({@link Exports}),这里按序收齐、一次写盘(写盘也在后台线程),结果回聊天栏说一句。主人取消对话框就什么也不要;
 * 断线或段序不对,这一份作废。
 *
 * <p>线程:客户端主线程(对话框与写盘在各自的后台线程里,结果回主线程)。
 */
public final class MemoryFiles {

    /** 选好了位置、正在收的一份。 */
    private record Incoming(Path file, Exports.Receiver parts) {}

    private static final Map<UUID, Incoming> INCOMING = new HashMap<>();

    private MemoryFiles() {}

    /** 点了"导出":问存哪儿,选定了才向服务端要这一份。 */
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
                    INCOMING.put(companion, new Incoming(Path.of(path), new Exports.Receiver()));
                    RemoteCompanions.getOrCreate(companion).requestExport();
                }
            });
        }, "numen-export-picker").start();
    }

    /** 服务端推来一段。 */
    static void receive(ExportChunkPayload chunk) {
        Incoming incoming = INCOMING.get(chunk.companion());
        if (incoming == null) {
            return;   // 没人要的(对话框取消了、断线后晚到的)
        }
        if (!incoming.parts().accept(chunk)) {
            INCOMING.remove(chunk.companion());
            failed(chunk.companionName());
            return;
        }
        if (incoming.parts().complete()) {
            INCOMING.remove(chunk.companion());
            byte[] zip = incoming.parts().bytes();
            new Thread(() -> {
                IOException error = null;
                try {
                    Files.write(incoming.file(), zip);
                } catch (IOException e) {
                    error = e;
                }
                IOException failure = error;
                Minecraft.getInstance().execute(() -> {
                    if (failure == null) {
                        ChatLines.notice(chunk.companionName(), I18n.get(Keys.EXPORT_SAVED, incoming.file().toString()));
                    } else {
                        Constants.LOG.warn("[numen-export] 写不了 {}: {}", incoming.file(), failure.toString());
                        failed(chunk.companionName());
                    }
                });
            }, "numen-export-writer").start();
        }
    }

    /** 断线:收了一半的作废。 */
    static void reset() {
        INCOMING.clear();
    }

    private static void failed(String name) {
        ChatLines.notice(name, I18n.get(Keys.EXPORT_FAILED, name));
    }
}
