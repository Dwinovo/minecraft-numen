package com.dwinovo.numen.plugins.litematica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 主人电脑上的 Litematica 图纸,与 Numen 服务端的图纸目录之间的那点事。
 *
 * <h2>两个目录</h2>
 * Litematica 把图纸存在它的“基础图纸目录”里:默认是 {@code <游戏目录>/schematics},主人在它的配置里
 * 可以改成别处。Numen 的服务端图纸目录是 {@code <服务端工作目录>/schematics};单机时服务端工作目录
 * 就是游戏目录,两者重合,连别人的服务器时则是两台机器、重合不成立。
 *
 * <h2>怎么知道 Litematica 配了哪个目录</h2>
 * 不引它的类、不用它的 API:直接读它的配置文件 {@code config/litematica.json},找
 * {@code customSchematicBaseDirectoryEnabled} 与 {@code customSchematicBaseDirectory} 两个字段
 * (它的配置按分类嵌套,这里递归找,不依赖分类名)。读不到或没开自定义,就用默认目录。
 *
 * <p>本类只在主人客户端上被调用,里面的原版客户端类也是那时才解析。
 */
final class Litematica {

    private Litematica() {}

    /** 认的图纸扩展名。 */
    static final String EXT = ".litematic";

    /** 主人 Litematica 的图纸目录:配置里开了自定义就用它,否则用默认的 {@code <游戏目录>/schematics}。 */
    static Path sourceDir() {
        Path game = gameDir();
        Path config = game.resolve("config").resolve("litematica.json");
        if (Files.isRegularFile(config)) {
            try (var reader = Files.newBufferedReader(config)) {
                JsonElement root = JsonParser.parseReader(reader);
                Boolean enabled = find(root, "customSchematicBaseDirectoryEnabled", JsonElement::getAsBoolean);
                String dir = find(root, "customSchematicBaseDirectory", JsonElement::getAsString);
                if (Boolean.TRUE.equals(enabled) && dir != null && !dir.isBlank()) {
                    return Path.of(dir);
                }
            } catch (RuntimeException | IOException e) {
                // 它的配置坏了不该把这里也带坏:退回默认目录,照常列出默认目录里的图纸。
            }
        }
        return game.resolve("schematics");
    }

    /** Numen 服务端的图纸目录。单机时服务端工作目录就是游戏目录,所以就是 {@code <游戏目录>/schematics}。 */
    static Path blueprintDir() {
        return gameDir().resolve("schematics");
    }

    /** 主人本机是不是正开着这个世界(集成服务器在跑)。连别人的服务器时为 false。 */
    static boolean hosting() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    /** 图纸目录里所有 {@code .litematic} 的名字(不含扩展名),排序稳定。 */
    static List<String> list() {
        Path dir = sourceDir();
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (var stream = Files.list(dir)) {
            stream.forEach(path -> {
                String file = path.getFileName().toString();
                if (Files.isRegularFile(path) && file.toLowerCase(Locale.ROOT).endsWith(EXT)) {
                    names.add(file.substring(0, file.length() - EXT.length()));
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("cannot list Litematica's schematics in " + dir, e);
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    /**
     * 把一张图纸拷进 Numen 的图纸目录。
     *
     * @return 是否真的拷了;false = 两边本来就是同一个文件(默认目录重合),本来就在
     * @throws IllegalArgumentException 图纸目录里没有这张
     */
    static boolean copy(String name) {
        Path from = sourceDir().resolve(name + EXT);
        if (!Files.isRegularFile(from)) {
            throw new IllegalArgumentException("no " + name + EXT + " in " + sourceDir());
        }
        Path to = blueprintDir().resolve(name + EXT);
        if (sameFile(from, to)) {
            return false;
        }
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            throw new RuntimeException("cannot copy " + from + " to " + to, e);
        }
    }

    /** 是同一个文件、或内容一致——都不必再拷一遍。 */
    private static boolean sameFile(Path a, Path b) {
        try {
            if (!Files.isRegularFile(b)) {
                return false;
            }
            return Files.isSameFile(a, b) || (Files.size(a) == Files.size(b) && Files.mismatch(a, b) < 0);
        } catch (IOException e) {
            return false;
        }
    }

    private static Path gameDir() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            throw new IllegalStateException("the game client is not running");
        }
        return client.gameDirectory.toPath();
    }

    /** 在 JSON 里递归找第一个叫 {@code name} 的字段并读它;没有、读不动返回 null。 */
    private static <T> T find(JsonElement root, String name, Function<JsonElement, T> read) {
        if (root == null) {
            return null;
        }
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (entry.getKey().equals(name)) {
                    try {
                        return read.apply(entry.getValue());
                    } catch (RuntimeException e) {
                        return null;
                    }
                }
                T nested = find(entry.getValue(), name, read);
                if (nested != null) {
                    return nested;
                }
            }
        } else if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                T nested = find(element, name, read);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
