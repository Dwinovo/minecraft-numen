package com.dwinovo.numen.api.client;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.LegacyMigration;
import com.dwinovo.numen.api.store.StoreException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 这台电脑自己的文件(界面偏好、语音输入的设置、会话的展示偏好,以及语音合成的 key)住在
 * {@code config/numen/client/}({@link com.dwinovo.numen.api.home.Homes#clientDir})。早先它们散放在 {@code config/numen/} 根下;
 * 这里把旧位置的接过来。其中旧版的声线库、皮肤库与每只同伴的声线皮肤选择({@code voice.json}、{@code skin.json}、{@code skins/}、
 * {@code looks.json})现在是服务端的库与她的绑定:单人游戏的服务端从 {@code client/} 里把它们迁进新布局
 * ({@code LegacyLooks}),这里只负责让它们先到 {@code client/}。
 *
 * <p>规矩同 {@link LegacyMigration}:复制,不移动(旧的原样留着;单人游戏里服务端也读 {@code config/numen/} 根下的旧文件,
 * 会话成员表、主动性档位),目标已经在就是迁过了,先放进暂存再改名所以可重来。
 */
public final class ClientFiles {

    /** 旧版散放在根下、现在属于这台电脑的文件与目录。 */
    private static final List<String> NAMES = List.of("ui.json", "looks.json", "skin.json", "skins", "voice.json",
            "stt.json", "conversation_prefs.json");

    private ClientFiles() {}

    /**
     * @param legacyRoot 旧的 {@code config/numen/}
     * @param clientDir  新的 {@code config/numen/client/}
     */
    public static void migrate(Path legacyRoot, Path clientDir) {
        for (String name : NAMES) {
            Path from = legacyRoot.resolve(name);
            try {
                boolean moved = Files.isDirectory(from) ? LegacyMigration.tree(from, clientDir.resolve(name))
                        : LegacyMigration.file(from, clientDir.resolve(name));
                if (moved) {
                    Constants.LOG.info("[numen-migrate] 客户端文件 {} 复制进了 client/", name);
                }
            } catch (StoreException e) {
                // 一项没复制成不拦着别的,也不拦着游戏启动;原件还在,下次启动再来
                Constants.LOG.warn("[numen-migrate] 复制 {} 失败,原件还在: {}", name, e.getMessage());
            }
        }
    }
}
