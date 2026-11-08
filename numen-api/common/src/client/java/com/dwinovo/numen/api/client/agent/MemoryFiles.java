package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.Exports;
import com.dwinovo.numen.api.brain.Segments;
import com.dwinovo.numen.api.client.chat.ChatLines;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.payload.ExportChunkPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 她的记忆导出到这台电脑上:先弹系统的保存对话框(SDL 的对话框是异步的,不冻住主循环),主人选定了位置才向服务端要;服务端的 zip
 * 按段依次下行({@link Exports}),这里按序收齐、一次写盘(写盘在后台线程),结果回聊天栏说一句。主人取消对话框就什么也不要;
 * 断线或段序不对,这一份作废。
 *
 * <p>线程:客户端主线程(对话框的回调可能在别的线程上来,写盘在后台线程里,结果都回主线程)。
 */
public final class MemoryFiles {

    /** 选好了位置、正在收的一份。 */
    private record Incoming(Path file, Segments.Assembler parts) {}

    private static final Map<UUID, Incoming> INCOMING = new HashMap<>();
    /** 对话框开着、还没答复的:编号随对话框交给 SDL,回调凭它认出是给谁问的。 */
    private static final Map<Long, UUID> ASKING = new HashMap<>();
    private static long nextAsk;

    private MemoryFiles() {}

    /**
     * 对话框的过滤器与回调:整个客户端共用一份,SDL 在回调之前都要读得到它们,所以常驻到进程结束。第一次导出时才建。
     */
    private static final class Picker {
        static final SDL_DialogFileFilter.Buffer ZIP = SDL_DialogFileFilter.calloc(1)
                .name(MemoryUtil.memUTF8("ZIP"))
                .pattern(MemoryUtil.memUTF8("zip"));
        static final SDL_DialogFileCallback PICKED = SDL_DialogFileCallback.create(MemoryFiles::picked);
    }

    /** 点了"导出":问存哪儿,选定了才向服务端要这一份。 */
    public static void ask(UUID companion, String name) {
        long ask = ++nextAsk;
        ASKING.put(ask, companion);
        int props = SDLProperties.SDL_CreateProperties();
        SDLProperties.SDL_SetPointerProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER,
                Minecraft.getInstance().getWindow().handle());
        SDLProperties.SDL_SetPointerProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_FILTERS_POINTER, Picker.ZIP.address());
        SDLProperties.SDL_SetNumberProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER, Picker.ZIP.remaining());
        SDLProperties.SDL_SetStringProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_LOCATION_STRING, name + "-memory.zip");
        SDLProperties.SDL_SetStringProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING, I18n.get(Keys.EXPORT_PICKER_TITLE));
        SDLDialog.SDL_ShowFileDialogWithProperties(SDLDialog.SDL_FILEDIALOG_SAVEFILE, Picker.PICKED, ask, props);
        SDLProperties.SDL_DestroyProperties(props);
    }

    /** 对话框答复了(可能在别的线程上):{@code files} 为空指针是出错,第一项为空是主人取消。 */
    private static void picked(long ask, long files, int filter) {
        String path = null;
        if (files == MemoryUtil.NULL) {
            Constants.LOG.warn("[numen-export] native file dialog unavailable: {}", SDLError.SDL_GetError());
        } else if (MemoryUtil.memGetAddress(files) != MemoryUtil.NULL) {
            path = MemoryUtil.memUTF8(MemoryUtil.memGetAddress(files));
        }
        String chosen = path;
        Minecraft.getInstance().execute(() -> {
            UUID companion = ASKING.remove(ask);
            if (companion != null && chosen != null) {
                INCOMING.put(companion, new Incoming(Path.of(chosen), new Segments.Assembler()));
                RemoteCompanions.getOrCreate(companion).requestExport();
            }
        });
    }

    /** 服务端推来一段。 */
    static void receive(ExportChunkPayload chunk) {
        Incoming incoming = INCOMING.get(chunk.companion());
        if (incoming == null) {
            return;   // 没人要的(对话框取消了、断线后晚到的)
        }
        if (!incoming.parts().accept(chunk.index(), chunk.total(), chunk.bytes())) {
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
