package com.dwinovo.numen.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.tag.TagKey;
import io.papermc.paper.tag.PreFlattenTagRegistrar;
import io.papermc.paper.tag.TagEntry;
import net.kyori.adventure.key.Key;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 把插件 jar 里的标签文件({@code data/<命名空间>/tags/block|item/**.json},数据包的写法)装进 Paper。
 *
 * <p>Paper 1.21.1 的插件不能带数据包,但有标签的生命周期事件:服务端每次装载标签(起服、{@code /reload})都会让插件在
 * "拍平"之前往标签里加条目。这个事件只认引导阶段({@code PluginBootstrap})登记的处理器,所以插件的引导类里调一次
 * {@link #register}。模组上这些文件是数据包,加载器自己读;Paper 读的是同一批文件,标签的内容因此只有一个出处
 * (手写的在 common 的资源里,数据生成的由 datagen 产出)。
 */
public final class PluginTags {

    private PluginTags() {}

    /** 一条标签条目:{@code ns:id} 或 {@code #ns:tag}(带井号的引用另一个标签),缺了它标签是否算失败由 required 定。 */
    private record Entry(String id, boolean required) {}

    /** 一个标签文件:数据包里的目录名(block、item)、标签 id、是替换还是追加、条目。 */
    private record TagFile(String dir, Key id, boolean replace, List<Entry> entries) {}

    /** 在引导阶段调:读出本插件 jar 里所有方块与物品标签,登记成每次装载标签时的补充。 */
    public static void register(BootstrapContext context) {
        List<TagFile> tags = readAll(context.getPluginSource());
        onFlatten(context, RegistryKey.BLOCK, "block", tags);
        onFlatten(context, RegistryKey.ITEM, "item", tags);
    }

    private static <T> void onFlatten(BootstrapContext context, RegistryKey<T> registry, String dir, List<TagFile> all) {
        List<TagFile> files = all.stream().filter(t -> t.dir().equals(dir)).toList();
        if (files.isEmpty()) {
            return;
        }
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.TAGS.preFlatten(registry), event -> {
            PreFlattenTagRegistrar<T> registrar = event.registrar();
            for (TagFile file : files) {
                TagKey<T> tag = TagKey.create(registry, file.id());
                List<TagEntry<T>> entries = file.entries().stream().map(e -> entry(registry, e)).toList();
                if (file.replace()) {
                    registrar.setTag(tag, entries);
                } else {
                    registrar.addToTag(tag, entries);
                }
            }
        });
    }

    private static <T> TagEntry<T> entry(RegistryKey<T> registry, Entry entry) {
        if (entry.id().startsWith("#")) {
            return TagEntry.tagEntry(TagKey.create(registry, Key.key(entry.id().substring(1))), entry.required());
        }
        return TagEntry.valueEntry(TypedKey.create(registry, Key.key(entry.id())), entry.required());
    }

    private static List<TagFile> readAll(Path jar) {
        List<TagFile> out = new ArrayList<>();
        try (FileSystem fs = FileSystems.newFileSystem(jar)) {
            Path data = fs.getPath("data");
            if (!Files.isDirectory(data)) {
                return out;
            }
            List<Path> jsons;
            try (Stream<Path> walk = Files.walk(data)) {
                jsons = walk.filter(f -> f.toString().endsWith(".json")).toList();
            }
            for (Path json : jsons) {
                Path relative = data.relativize(json); // ns/tags/block/a/b.json
                if (relative.getNameCount() >= 4 && relative.getName(1).toString().equals("tags")) {
                    out.add(read(relative, json));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读不出 " + jar + " 里的标签文件", e);
        }
        return out;
    }

    /** {@code ns/tags/block/a/b.json} → 方块标签 {@code ns:a/b}。 */
    private static TagFile read(Path relative, Path file) throws IOException {
        String name = relative.subpath(3, relative.getNameCount()).toString().replace('\\', '/');
        name = name.substring(0, name.length() - ".json".length());
        try (Reader in = Files.newBufferedReader(file)) {
            JsonObject json = JsonParser.parseReader(in).getAsJsonObject();
            List<Entry> entries = new ArrayList<>();
            for (JsonElement value : json.getAsJsonArray("values")) {
                if (value.isJsonObject()) {
                    JsonObject object = value.getAsJsonObject();
                    entries.add(new Entry(object.get("id").getAsString(),
                            !object.has("required") || object.get("required").getAsBoolean()));
                } else {
                    entries.add(new Entry(value.getAsString(), true));
                }
            }
            return new TagFile(relative.getName(2).toString(), Key.key(relative.getName(0).toString(), name),
                    json.has("replace") && json.get("replace").getAsBoolean(), entries);
        }
    }
}
