package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 每只同伴用哪条声线、穿的是皮肤库里哪一张——这台机器的展示偏好(朗读与皮肤库都在这台客户端上),落在 {@code looks.json},
 * 同伴不在了不影响别的。她的大脑、人设、档案在服务端,和这里无关。
 *
 * <p>第一次读不到 {@code looks.json} 时,从旧版放在 {@code companions/<uuid>/binding.json} 里的声线与皮肤两项接过来
 * (旧文件原样留着)。
 *
 * <p>客户端主线程专用。
 */
public final class CompanionLooks {

    /** 一只同伴的外观选择;{@code null} = 没选。 */
    public record Looks(String voiceId, String skinId) {

        public static final Looks EMPTY = new Looks(null, null);

        public Looks withVoice(String id) {
            return new Looks(blankToNull(id), skinId);
        }

        public Looks withSkin(String id) {
            return new Looks(voiceId, blankToNull(id));
        }

        public boolean isEmpty() {
            return voiceId == null && skinId == null;
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }

    private static final String FILE = "looks.json";
    private static final String LEGACY_DIR = "companions";
    private static final String LEGACY_BINDING = "binding.json";

    private static Path root;
    private static Path legacyRoot;
    private static final Map<UUID, Looks> LOOKS = new HashMap<>();

    private CompanionLooks() {}

    /**
     * 安家的位置——这台电脑的目录({@code <config>/numen/client/})。由 loader 给,不问 {@code Minecraft}(datagen 里模组照样构造);
     * 测试传临时目录,走的是同一条路。传 {@code null} 是放手。
     *
     * @param legacy 旧的 {@code <config>/numen/}:第一次读不到 {@code looks.json} 时,旧的同伴绑定在它的 {@code companions/} 下
     */
    public static void init(Path clientDir, Path legacy) {
        root = clientDir;
        legacyRoot = legacy;
        LOOKS.clear();
        if (root == null) {
            return;
        }
        Path file = root.resolve(FILE);
        if (Files.isRegularFile(file)) {
            read(file);
        } else {
            seedFromLegacy();
        }
    }

    /** 这只同伴的选择;没选过是 {@link Looks#EMPTY}。 */
    public static Looks of(UUID companion) {
        return LOOKS.getOrDefault(companion, Looks.EMPTY);
    }

    /** 记下这只同伴的选择(全空就是忘掉她)。 */
    public static void set(UUID companion, Looks looks) {
        if (looks == null || looks.isEmpty()) {
            LOOKS.remove(companion);
        } else {
            LOOKS.put(companion, looks);
        }
        save();
    }

    /** 她不在了:这条选择没有用了。 */
    public static void forget(UUID companion) {
        if (LOOKS.remove(companion) != null) {
            save();
        }
    }

    // ---- 落盘 ----

    private static JsonDoc doc(Path file) {
        return new JsonDoc(file, JsonDoc.Format.of(1), NumenIo.inline("numen-looks"));
    }

    private static void read(Path file) {
        try {
            doc(file).read().filter(o -> o.get("companions") instanceof JsonObject).ifPresent(o -> o.getAsJsonObject("companions")
                    .entrySet().forEach(e -> {
                if (e.getValue().isJsonObject()) {
                    JsonObject l = e.getValue().getAsJsonObject();
                    try {
                        LOOKS.put(UUID.fromString(e.getKey()), new Looks(str(l, "voice"), str(l, "skin")));
                    } catch (IllegalArgumentException notUuid) {
                        Constants.LOG.warn("[numen-looks] 跳过读不懂的一条: {}", e.getKey());
                    }
                }
            }));
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-looks] {} 读不了,当作都没选过: {}", file.getFileName(), unreadable.toString());
        }
    }

    private static void seedFromLegacy() {
        Path legacy = legacyRoot.resolve(LEGACY_DIR);
        if (!Files.isDirectory(legacy)) {
            return;
        }
        try (Stream<Path> homes = Files.list(legacy)) {
            for (Path home : homes.toList()) {
                Path binding = home.resolve(LEGACY_BINDING);
                if (!Files.isRegularFile(binding)) {
                    continue;
                }
                try {
                    UUID companion = UUID.fromString(home.getFileName().toString());
                    JsonObject o = doc(binding).read().orElse(new JsonObject());
                    Looks looks = new Looks(str(o, "voice"), str(o, "skin"));
                    if (!looks.isEmpty()) {
                        LOOKS.put(companion, looks);
                    }
                } catch (RuntimeException notHome) {
                    Constants.LOG.warn("[numen-looks] 旧绑定 {} 读不了,跳过: {}", binding, notHome.toString());
                }
            }
        } catch (IOException e) {
            Constants.LOG.warn("[numen-looks] 读不了旧的同伴目录 {}: {}", legacy, e.toString());
        }
        if (!LOOKS.isEmpty()) {
            save();
        }
    }

    private static void save() {
        if (root == null) {
            return;
        }
        // 同伴的 UUID 不能和文档自己的版本号字段共用一层
        JsonObject all = new JsonObject();
        LOOKS.forEach((companion, looks) -> {
            JsonObject l = new JsonObject();
            if (looks.voiceId() != null) l.addProperty("voice", looks.voiceId());
            if (looks.skinId() != null) l.addProperty("skin", looks.skinId());
            all.add(companion.toString(), l);
        });
        JsonObject out = new JsonObject();
        out.add("companions", all);
        doc(root.resolve(FILE)).write(out);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && !o.get(key).getAsString().isBlank()
                ? o.get(key).getAsString() : null;
    }
}
