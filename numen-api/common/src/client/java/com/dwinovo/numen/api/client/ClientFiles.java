package com.dwinovo.numen.api.client;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.store.StoreException;
import com.dwinovo.numen.api.store.Trees;

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
                boolean moved = Files.isDirectory(from) ? tree(from, clientDir.resolve(name))
                        : file(from, clientDir.resolve(name));
                if (moved) {
                    Constants.LOG.info("[numen-migrate] client file {} was copied into client/", name);
                }
            } catch (StoreException e) {
                // 一项没复制成不拦着别的,也不拦着游戏启动;原件还在,下次启动再来
                Constants.LOG.warn("[numen-migrate] copying {} failed, the original is still there: {}", name, e.getMessage());
            }
        }
    }

    private static final String STAGING = ".migrating";

    /** 一个目录整个搬过去:目标不在、旧的在才搬。复制到暂存再改名,目标要么不在、要么完整。 */
    private static boolean tree(Path legacy, Path target) {
        if (!Files.isDirectory(legacy) || Files.exists(target)) {
            return false;
        }
        Path staging = target.resolveSibling(target.getFileName() + STAGING);
        Trees.delete(staging);   // 上次崩在半路留下的
        Trees.copy(legacy, staging);
        verify(legacy, staging);
        place(staging, target);
        return true;
    }

    /** 一个文件搬过去:目标不在、旧的在才搬;先复制到暂存再改名,目标要么不在、要么完整。 */
    private static boolean file(Path legacy, Path target) {
        if (Files.exists(target) || !Files.isRegularFile(legacy)) {
            return false;
        }
        Path staging = target.resolveSibling(target.getFileName() + STAGING);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(legacy, staging, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new StoreException("cannot move " + legacy + ": " + e, e);
        }
        place(staging, target);
        return true;
    }

    /** 复制出来的每个文件和旧的一样大。 */
    private static void verify(Path from, Path copy) {
        try (java.util.stream.Stream<Path> walk = Files.walk(copy)) {
            for (Path made : walk.filter(Files::isRegularFile).toList()) {
                Path source = from.resolve(copy.relativize(made).toString());
                if (!Files.isRegularFile(source) || Files.size(source) != Files.size(made)) {
                    throw new StoreException("the copy " + made + " does not match the old one");
                }
            }
        } catch (java.io.IOException e) {
            throw new StoreException("cannot verify " + copy + ": " + e, e);
        }
    }

    private static void place(Path staging, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(staging, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            throw new StoreException("cannot put into " + target + ": " + e, e);
        }
    }
}
