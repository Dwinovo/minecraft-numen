package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 皮肤就是文件夹里的 png,文件名就是皮肤名:玩家只往里放图,没有"新建、改名、删除"的条目。客户端列它自己机器上的文件夹(那是"我的"),
 * 服务端列服务器上的(那是"服务器提供");两头用的是同一份列举,只是根目录不同。
 */
public final class SkinFolder {

    /** 文件夹里的一张图。名字是去掉 {@code .png} 的文件名;大小与修改时刻供界面判断图换过没有。 */
    public record Entry(String name, long size, long modified) {}

    private static final String SUFFIX = ".png";

    private final Path dir;

    public SkinFolder(Path dir) {
        this.dir = dir;
    }

    /** 这台机器的皮肤文件夹({@link NumenPaths#skins})。 */
    public static SkinFolder here() {
        return new SkinFolder(NumenPaths.skins());
    }

    public Path dir() {
        return dir;
    }

    /** 文件夹里的全部 png,按名字排;文件夹不在是空表(玩家还没放过)。 */
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        files().forEach((name, file) -> {
            try {
                out.add(new Entry(name, Files.size(file), Files.getLastModifiedTime(file).toMillis()));
            } catch (IOException vanished) {
                // 列的这一刻被删了:就当它不在
            }
        });
        return out;
    }

    /**
     * 读叫这个名字的那张图,检查过能当皮肤用({@link SkinImage#check})。名字只认文件夹里列得出来的,不拼路径——
     * 远端传来的名字不会指到文件夹外面。
     *
     * @throws SkinError {@link SkinError#BAD_IMAGE} 没有这张图、读不了、不能当皮肤用
     */
    public byte[] read(String name) {
        Path file = files().get(name);
        if (file == null) {
            throw SkinError.of(SkinError.BAD_IMAGE, "there is no skin \"" + name + "\" in the skins folder");
        }
        byte[] png;
        try {
            png = Files.readAllBytes(file);
        } catch (IOException e) {
            throw SkinError.of(e, SkinError.BAD_IMAGE, "the file cannot be read");
        }
        SkinImage.check(png);
        return png;
    }

    /** 名字 → 文件(扩展名大小写不拘),按名字排。 */
    private Map<String, Path> files() {
        Map<String, Path> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path file : children.filter(Files::isRegularFile).toList()) {
                String leaf = file.getFileName().toString();
                if (leaf.toLowerCase(Locale.ROOT).endsWith(SUFFIX)) {
                    out.put(leaf.substring(0, leaf.length() - SUFFIX.length()), file);
                }
            }
        } catch (IOException e) {
            Constants.LOG.warn("[numen-skin] 读不了皮肤文件夹 {}: {}", dir, e.toString());
        }
        return out;
    }
}
