package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.skin.SkinCache;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.skin.SkinRef;
import com.dwinovo.numen.api.store.Atomic;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.dwinovo.numen.api.store.StoreException;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 旧的皮肤库({@code <根>/skin/<命名空间>/<名字>.json},一条是手臂模型、Mojang 签名数据和原图)搬进现在的样子:皮肤是文件夹里的 png,
 * 签好的数据在签名缓存({@link SkinCache}),同伴身上记的是对缓存的引用。规矩同 {@link LegacyMigration}:<b>可重来</b>——
 * 同一条再搬一次不会多出文件(同名同内容复用那个文件、不同内容才改名 {@code 名字_2}),缓存是按内容寻址的、再存一次还是它;
 * 搬完旧的库目录由调用方删掉,没有什么可再搬的。
 *
 * <ul>
 *   <li>有原图的导出成 {@code <皮肤文件夹>/<条目名>.png}(玩家本机或房主自己的机器上;连专用服务器的玩家的图由服务端下发,见
 *       {@link SkinHandover});</li>
 *   <li>签好的(有 value)原样放进缓存,键是原图的哈希加手臂模型;没有原图的(当年借来的正版皮肤取不到图)键取签名数据的哈希——
 *       它不会出现在文件夹里,但穿着它的同伴照穿不误;</li>
 *   <li>同伴绑定里记的旧条目名({@code 命名空间:名字})换成对缓存的引用({@link #rebind})。</li>
 * </ul>
 */
public final class SkinMigration {

    private SkinMigration() {}

    /**
     * 一个旧皮肤库整个搬过去。
     *
     * @param library      旧库的目录({@code <根>/skin})
     * @param skins        皮肤文件夹
     * @param exportImages 把原图导出到皮肤文件夹;连专用服务器的玩家的库不导(那些图在服务端,由 {@link SkinHandover} 交到他本机)
     * @return 旧条目的名字({@code 命名空间:名字})→ 它在缓存里的引用;没签过名的不在表里
     */
    public static Map<String, SkinRef> library(Path library, Path skins, SkinCache cache, boolean exportImages) {
        Map<String, SkinRef> ids = new LinkedHashMap<>();
        if (!Files.isDirectory(library)) {
            return ids;
        }
        for (Path space : children(library)) {
            if (!Files.isDirectory(space)) {
                continue;
            }
            for (Path file : children(space)) {
                String leaf = file.getFileName().toString();
                if (!Files.isRegularFile(file) || !leaf.endsWith(".json")) {
                    continue;
                }
                String name = leaf.substring(0, leaf.length() - ".json".length());
                SkinRef ref;
                try {
                    ref = carryOver(name, SkinProfile.read(Files.readString(file, StandardCharsets.UTF_8)), skins, cache,
                            exportImages);
                } catch (IOException | RuntimeException unreadable) {
                    Constants.LOG.warn("[numen-migrate] 旧皮肤 {} 读不了,跳过: {}", file, unreadable.toString());
                    continue;
                }
                if (ref != null) {
                    ids.put(space.getFileName() + ":" + name, ref);
                }
            }
        }
        return ids;
    }

    /**
     * 一条旧皮肤搬成现在的样子:有原图就导出成 png(需要时),签好的放进缓存。
     *
     * @return 穿它该记的缓存引用;没签过名的是 {@code null}
     */
    public static SkinRef carryOver(String name, SkinProfile skin, Path skins, SkinCache cache, boolean exportImage) {
        if (exportImage && skin.png().length > 0) {
            place(skins, name, skin.png());
        }
        if (!skin.signed()) {
            return null;
        }
        SkinRef ref = skin.png().length > 0 ? SkinRef.of(skin.png(), skin.variant())
                : SkinRef.of(skin.value().getBytes(StandardCharsets.UTF_8), skin.variant());
        cache.put(ref, skin);
        return ref;
    }

    /**
     * 一张图放进皮肤文件夹,返回它的名字(去掉 {@code .png}):文件夹里已经有同样内容的(同名或 {@code 名字_N})就是它,
     * 不再多放一份;名字被别的图占了就用 {@code 名字_2}、{@code 名字_3}……
     */
    public static String place(Path skins, String name, byte[] png) {
        String candidate = name;
        for (int i = 2;; i++) {
            Path file = skins.resolve(candidate + ".png");
            if (!Files.exists(file)) {
                Atomic.write(file, png);
                return candidate;
            }
            if (Arrays.equals(read(file), png)) {
                return candidate;
            }
            candidate = name + "_" + i;
        }
    }

    /**
     * 她们绑定里记的旧条目名换成对缓存的引用。已经是引用的、没记皮肤的不动;表里没有的旧名字(没签过名的、库里早已删掉的)去掉——
     * 绑定里不留一个指不到任何东西的名字,身上穿着的仍在名册里。
     *
     * @param ids 旧条目名 → 引用
     * @return 改了几个文件
     */
    public static int rebind(List<Path> bindingFiles, Map<String, SkinRef> ids) {
        int changed = 0;
        for (Path file : bindingFiles) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            JsonDoc doc = new JsonDoc(file, JsonDoc.Format.of(1), Io.direct(e -> {
                throw e;
            }));
            JsonObject binding = doc.read().orElse(new JsonObject());
            if (!(binding.get("skin") instanceof JsonPrimitive worn) || SkinRef.parse(worn.getAsString()) != null) {
                continue;
            }
            SkinRef ref = ids.get(worn.getAsString());
            if (ref == null) {
                binding.remove("skin");
            } else {
                binding.addProperty("skin", ref.toString());
            }
            if (binding.entrySet().isEmpty()) {
                doc.delete();
            } else {
                doc.write(binding);
            }
            changed++;
        }
        return changed;
    }

    private static List<Path> children(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.sorted().toList();
        } catch (IOException e) {
            throw new StoreException("读不了 " + dir + ": " + e, e);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new StoreException("读不了 " + file + ": " + e, e);
        }
    }
}
