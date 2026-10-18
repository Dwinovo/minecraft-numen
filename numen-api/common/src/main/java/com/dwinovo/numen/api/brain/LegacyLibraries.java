package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.skill.SkillInfo;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Fingerprint;
import com.dwinovo.numen.api.library.Switches;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.store.Atomic;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.dwinovo.numen.api.store.StoreException;
import com.dwinovo.numen.api.store.Trees;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 旧布局的"库"搬进新布局(单人游戏的房主;多人服不迁)。旧版把模型档案(连 key)、MCP 服务器、技能放在 {@code config/numen/}
 * 根下一个个散文件,人设与 Lua 模块放在 {@code players/<房主>/} 里一层平铺;新布局统一成 {@code <库>/<命名空间>/<名字>},
 * key 进钥匙串({@code docs/input-pipeline.md} §六)。规矩同 {@link LegacyMigration}:<b>复制不移动</b>(旧的原样留着),
 * <b>只做一次</b>(目标在了就是迁过了),<b>可重来</b>(先放进暂存再整个改名)。
 *
 * <ul>
 *   <li>旧的模型档案是服主的,进内置组({@code server/provider/server/}),key 按厂家进服主的钥匙串;
 *       同一个厂家有几把不同的 key,留第一把;</li>
 *   <li>旧的人设与技能、改过的模块是房主自己的,进他的"我的"({@code my} 命名空间)。和随 Numen 发的内置一模一样的不搬
 *       (旧版把示例人设与出厂模块都装成了文件);</li>
 *   <li>旧版"删掉的出厂模块不再装回",现在是房主对它的开关;旧版全局的技能开关,现在是房主的开关;</li>
 *   <li>同伴的绑定里记的档案、人设的 id 随之换成新的({@code 命名空间:名字}),返回的对照表给 {@link #bindings} 用。</li>
 * </ul>
 */
public final class LegacyLibraries {

    private LegacyLibraries() {}

    // ==================== 模型档案 ====================

    /**
     * 旧的 {@code providers.json} → 内置组里一人一档案,key 进服主的钥匙串。
     *
     * @param serverDir 服务器目录 {@code config/numen/server/}
     * @return 旧档案的 id → 新 id;没有旧文件是空表。目标已经在时也算得出同一张对照表(绑定还要用)
     */
    public static Map<String, String> providers(Path legacyFile, Path serverDir) {
        if (!Files.isRegularFile(legacyFile)) {
            return Map.of();
        }
        JsonObject root = new JsonDoc(legacyFile, PROVIDERS_FORMAT, Io.direct(e -> {
            throw e;
        })).read().orElse(new JsonObject());
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> files = new LinkedHashMap<>();   // 名字 → 档案正文
        Map<String, String> keys = new LinkedHashMap<>();    // 厂家 → key
        if (root.get("entries") instanceof JsonArray entries) {
            for (JsonElement el : entries) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject entry = el.getAsJsonObject();
                String oldId = string(entry, "id");
                String base = EntryId.clean(string(entry, "name"), "profile");
                String name = base;
                for (int i = 2; files.containsKey(name); i++) {
                    name = base + "_" + i;
                }
                var profile = ProviderLibrary.profileFromJson(entry);
                files.put(name, ProviderLibrary.write(profile));
                if (oldId != null) {
                    ids.put(oldId, EntryId.SERVER + ":" + name);
                }
                String key = string(entry, "api_key");
                if (key != null && !key.isBlank() && profile.provider() != null && EntryId.segment(profile.provider())) {
                    keys.putIfAbsent(profile.provider(), key.strip());
                }
            }
        }
        LegacyMigration.stage(serverDir.resolve("provider").resolve(EntryId.SERVER), staging -> files.forEach((name, text) ->
                Atomic.write(staging.resolve(name + ".json"), text)));
        keys.forEach((service, key) -> {
            Path file = serverDir.resolve("keys").resolve(service + ".key");
            if (!Files.exists(file)) {
                Atomic.write(file, key);
            }
        });
        return ids;
    }

    /** 第 1 版:厂家 id 是各家自己的叫法;第 2 版起是 models.dev 的 id。 */
    private static final JsonDoc.Format PROVIDERS_FORMAT = JsonDoc.Format.of(2).upgrade(1, LegacyLibraries::toModelsDevIds);

    /** 第 2 版之前的库在根上记过的"已迁过"标记;有它的库厂家 id 早已是 models.dev 的 id,只差一个版本号。 */
    private static final String LEGACY_MARKER = "models_dev_ids";

    /** 老厂家 id(含玩家顺手会填的别名)→ models.dev 的厂家 id。 */
    private static final Map<String, String> LEGACY_PROVIDER_IDS = Map.ofEntries(
            Map.entry("kimi", "moonshotai"), Map.entry("moonshot", "moonshotai"),
            Map.entry("doubao", "volcengine"), Map.entry("ark", "volcengine"),
            Map.entry("qwen", "alibaba-cn"), Map.entry("tongyi", "alibaba-cn"),
            Map.entry("aliyun", "alibaba-cn"), Map.entry("dashscope", "alibaba-cn"),
            Map.entry("glm", "zhipuai"), Map.entry("zhipu", "zhipuai"),
            Map.entry("silicon", "siliconflow-cn"), Map.entry("siliconflow", "siliconflow-cn"),
            Map.entry("gemini", "google"), Map.entry("grok", "xai"),
            Map.entry("openai-compatible", "openai"));

    /** 本地部署的老厂家 id → 它们当年的缺省地址;升级成自定义厂家(OpenAI 兼容协议),地址写进档案。 */
    private static final Map<String, String> LEGACY_LOCAL_PROVIDERS = Map.of(
            "ollama", "http://localhost:11434/v1",
            "lmstudio", "http://localhost:1234/v1",
            "vllm", "http://localhost:8000/v1");

    /**
     * 档案里的老厂家 id 换成 models.dev 的 id;{@code ollama}、{@code lmstudio}、{@code vllm} 变成自定义厂家,
     * 玩家填过的地址不动,别的字段原样保留。
     */
    private static JsonObject toModelsDevIds(JsonObject root) {
        if (root.remove(LEGACY_MARKER) != null || !(root.get("entries") instanceof JsonArray entries)) {
            return root;
        }
        for (JsonElement el : entries) {
            if (!el.isJsonObject() || !(el.getAsJsonObject().get("provider") instanceof JsonPrimitive)) {
                continue;
            }
            JsonObject entry = el.getAsJsonObject();
            String old = entry.get("provider").getAsString().strip().toLowerCase(Locale.ROOT);
            if (LEGACY_LOCAL_PROVIDERS.containsKey(old)) {
                entry.addProperty("provider", "custom");
                entry.addProperty("protocol", "chat");
                if (!entry.has("base_url") || entry.get("base_url").getAsString().isBlank()) {
                    entry.addProperty("base_url", LEGACY_LOCAL_PROVIDERS.get(old));
                }
            } else if (LEGACY_PROVIDER_IDS.containsKey(old)) {
                entry.addProperty("provider", LEGACY_PROVIDER_IDS.get(old));
            }
        }
        return root;
    }

    // ==================== 人设 ====================

    /**
     * 平铺的人设目录({@code *.md})→ 房主的"我的"。和随 Numen 发的内置一模一样的不搬,绑定改指内置。
     *
     * @param flat      旧的人设目录
     * @param hostMy    房主的 {@code persona/my/}
     * @param serverDir 服务器目录(内置在 {@code persona/<命名空间>/})
     * @return 旧人设名 → 新 id
     */
    public static Map<String, String> personas(Path flat, Path hostMy, Path serverDir) {
        Map<String, String> ids = new LinkedHashMap<>();
        if (!Files.isDirectory(flat)) {
            return ids;
        }
        Set<String> toCopy = new HashSet<>();
        try (Stream<Path> files = Files.list(flat)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".md")).sorted().toList()) {
                String name = file.getFileName().toString().replaceFirst("\\.md$", "");
                if (!EntryId.segment(name)) {
                    continue;
                }
                String builtin = builtinWithSameText(serverDir.resolve(PersonaLibrary.KIND.dir()), name + ".md", file);
                if (builtin != null) {
                    ids.put(name, builtin + ":" + name);
                } else {
                    ids.put(name, EntryId.MINE + ":" + name);
                    toCopy.add(file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new StoreException("读不了旧的人设目录 " + flat + ": " + e, e);
        }
        if (!toCopy.isEmpty()) {
            LegacyMigration.stage(hostMy, staging -> Trees.copy(flat, staging, toCopy::contains));
        }
        return ids;
    }

    /** 内置里同名且内容一模一样的那一份的命名空间;没有是 null。 */
    private static String builtinWithSameText(Path libraryDir, String fileName, Path legacyFile) {
        if (!Files.isDirectory(libraryDir)) {
            return null;
        }
        try (Stream<Path> spaces = Files.list(libraryDir)) {
            for (Path space : spaces.filter(Files::isDirectory).toList()) {
                Path builtin = space.resolve(fileName);
                if (Files.isRegularFile(builtin) && Fingerprint.of(Files.readAllBytes(builtin))
                        .equals(Fingerprint.of(Files.readAllBytes(legacyFile)))) {
                    return space.getFileName().toString();
                }
            }
        } catch (IOException e) {
            throw new StoreException("读不了 " + libraryDir + ": " + e, e);
        }
        return null;
    }

    // ==================== Lua 模块 ====================

    /**
     * 旧的模块目录({@code <命名空间>/<组>.lua} 加账本)→ 房主的 {@code modules/}。旧版把出厂模块都装成了文件:和内置一模一样的
     * 不搬(它们现在就是内置);改过的和她自己的搬;账本整份搬。出厂的她删过的(账本记着、文件没了),现在是房主对它的开关。
     *
     * @param serverDir 服务器目录(内置在 {@code modules/<命名空间>/})
     */
    public static void modules(Path legacy, Path hostModules, Path serverDir, Switches hostSwitches) {
        if (!Files.isDirectory(legacy)) {
            return;
        }
        Path builtin = serverDir.resolve(Modules.KIND.dir());
        String ext = Modules.KIND.suffix();
        LegacyMigration.stage(hostModules, staging -> {
            Trees.copy(legacy, staging, top -> !Files.isRegularFile(legacy.resolve(top)) || top.equals("modules.json"));
            try (Stream<Path> files = Files.walk(staging)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(ext)).toList()) {
                    Path same = builtin.resolve(staging.relativize(file).toString());
                    if (Files.isRegularFile(same) && Fingerprint.of(Files.readAllBytes(same))
                            .equals(Fingerprint.of(Files.readAllBytes(file)))) {
                        Files.delete(file);
                    }
                }
            } catch (IOException e) {
                throw new StoreException("整理不了 " + staging + ": " + e, e);
            }
            deleteEmptyDirs(staging);
        });
        JsonObject ledger = new JsonDoc(legacy.resolve("modules.json"), JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        })).read().orElse(new JsonObject());
        for (var e : ledger.entrySet()) {
            int dot = e.getKey().indexOf('.');
            if (dot < 0 || !(e.getValue() instanceof JsonObject entry) || !entry.has("factory")) {
                continue;
            }
            Path file = legacy.resolve(e.getKey().substring(0, dot)).resolve(e.getKey().substring(dot + 1) + ext);
            Path shipped = builtin.resolve(e.getKey().substring(0, dot)).resolve(e.getKey().substring(dot + 1) + ext);
            if (!Files.exists(file) && Files.isRegularFile(shipped)) {
                hostSwitches.set(Modules.KIND.dir(), new EntryId(e.getKey().substring(0, dot),
                        e.getKey().substring(dot + 1)), false);
            }
        }
    }

    private static void deleteEmptyDirs(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path dir : walk.filter(Files::isDirectory).sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!dir.equals(root)) {
                    Trees.deleteIfEmpty(dir);
                }
            }
        } catch (IOException e) {
            throw new StoreException("整理不了 " + root + ": " + e, e);
        }
    }

    // ==================== 技能 ====================

    /**
     * 旧的技能目录({@code skills/<技能>/SKILL.md},玩家自己放的)→ 房主的 {@code skills/my/}。旧的全局开关(按技能名记的)
     * 换成房主按库里名字记的开关。
     *
     * @param legacySkills 旧的 {@code config/numen/skills}
     * @param legacyState  旧的 {@code config/numen/skills_state.json}
     * @param hostMy       房主的 {@code skills/my/}
     * @param serverSkills 服务器目录里内置的 {@code skills/}
     */
    public static void skills(Path legacySkills, Path legacyState, Path hostMy, Path serverSkills, Switches hostSwitches) {
        LegacyMigration.tree(legacySkills, hostMy);
        if (!Files.isRegularFile(legacyState)) {
            return;
        }
        JsonObject state = new JsonDoc(legacyState, JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        })).read().orElse(new JsonObject());
        if (!(state.get("disabled") instanceof JsonArray names)) {
            return;
        }
        Set<String> off = new HashSet<>();
        names.forEach(n -> off.add(n.getAsString()));
        for (Path root : List.of(serverSkills, hostMy.getParent())) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> spaces = Files.list(root)) {
                for (Path space : spaces.filter(Files::isDirectory).toList()) {
                    try (Stream<Path> dirs = Files.list(space)) {
                        for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                            SkillInfo info = SkillRegistry.read(dir);
                            if (info != null && off.contains(info.name()) && EntryId.segment(dir.getFileName().toString())) {
                                hostSwitches.set(SkillRegistry.KIND.dir(),
                                        new EntryId(space.getFileName().toString(), dir.getFileName().toString()), false);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new StoreException("读不了 " + root + ": " + e, e);
            }
        }
    }

    // ==================== 接进来的 MCP 服务器 ====================

    /** 旧的 {@code mcp_clients.json}(一份配置、里面一串服务器)→ 内置组里一台一个文件;总开关关着的,每台都写成不连。 */
    public static void mcp(Path legacyFile, Path serverMcp) {
        if (!Files.isRegularFile(legacyFile)) {
            return;
        }
        JsonObject root = new JsonDoc(legacyFile, JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        })).read().orElse(new JsonObject());
        boolean masterOn = !root.has("enabled") || root.get("enabled").getAsBoolean();
        Map<String, String> files = new LinkedHashMap<>();
        if (root.get("servers") instanceof JsonArray servers) {
            for (JsonElement el : servers) {
                if (!el.isJsonObject()) {
                    continue;
                }
                String base = EntryId.clean(string(el.getAsJsonObject(), "name"), "server");
                String name = base;
                for (int i = 2; files.containsKey(name); i++) {
                    name = base + "_" + i;
                }
                McpClientConfig.ServerSpec spec = McpClientConfig.parseServer(name, el.getAsJsonObject());
                files.put(name, McpClientConfig.write(masterOn ? spec : McpClientConfig.withEnabled(spec, false)));
            }
        }
        LegacyMigration.stage(serverMcp.resolve(EntryId.SERVER), staging -> files.forEach((name, text) ->
                Atomic.write(staging.resolve(name + ".json"), text)));
    }

    // ==================== 绑定 ====================

    /**
     * 同伴绑定里的档案与人设 id 换成新的。绑定记的人设是旧名字(没有命名空间),档案是旧 id。已经是新写法的不动。
     *
     * @return 改了几个文件
     */
    public static int bindings(List<Path> bindingFiles, Map<String, String> providerIds, Map<String, String> personaIds) {
        int changed = 0;
        for (Path file : bindingFiles) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            JsonDoc doc = new JsonDoc(file, JsonDoc.Format.of(1), Io.direct(e -> {
                throw e;
            }));
            JsonObject binding = doc.read().orElse(new JsonObject());
            boolean edited = false;
            String provider = string(binding, "provider");
            if (provider != null && providerIds.containsKey(provider)) {
                binding.addProperty("provider", providerIds.get(provider));
                edited = true;
            }
            String persona = string(binding, "persona");
            if (persona != null && !persona.contains(":")) {
                binding.addProperty("persona", personaIds.getOrDefault(persona, EntryId.MINE + ":" + persona));
                edited = true;
            }
            if (edited) {
                doc.write(binding);
                changed++;
            }
        }
        if (changed > 0) {
            Constants.LOG.info("[numen-migrate] {} 只同伴的绑定换成了新的库名字", changed);
        }
        return changed;
    }

    private static String string(JsonObject o, String key) {
        return o.get(key) instanceof JsonPrimitive p ? p.getAsString() : null;
    }
}
