package com.dwinovo.numen.agent.skill;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Client-side registry of skills. Skills are markdown directories the LLM can
 * load on demand via the {@code load_skill} tool, deferring long "how to do X"
 * instructions out of the system prompt and into the tool-call conversation
 * only when relevant.
 *
 * <h2>Why client-side only</h2>
 * Skills feed the LLM, and the LLM runs on the player's client (each player
 * pays their own tokens). Dedicated servers don't even instantiate this
 * registry — {@code load_skill} is an {@code isLocal} tool so its execute
 * path never crosses the network.
 *
 * <h2>Two sources, scanned in place and merged</h2>
 * Following the same model as Claude Code and opencode, skills are read from
 * multiple roots and merged by name — nothing is ever copied between them:
 * <ul>
 *   <li><b>Bundled (mod) skills</b> — a mod {@linkplain #declareBundled declares}
 *       a skills root inside its own jar; it is read <em>in place</em> from the
 *       jar every {@link #scan}, so it is never stale and vanishes when the mod
 *       is removed.</li>
 *   <li><b>Player skills</b> — {@code config/numen/skills} on disk, the
 *       {@code userRoot} passed to {@link #scan}. Plain files the player owns.</li>
 * </ul>
 * On a name clash the <b>player skill wins</b> (it is scanned last, on top of
 * the bundled set) — so a player re-tunes a mod's skill by dropping a same-named
 * directory in their config, exactly the Claude Code "any level overrides a
 * bundled skill" rule.
 *
 * <h2>Layout — one directory per skill</h2>
 * A skill is a whole <em>directory</em>, not a lone file: {@code SKILL.md} is
 * required (its {@code name:}/{@code description:} frontmatter is what the model
 * sees), and the directory may carry any supporting material the body points at.
 * <pre>
 * &lt;root&gt;/
 *   &lt;name&gt;/
 *     SKILL.md       (required — YAML frontmatter + markdown body)
 *     references/    (optional — extra docs the SKILL.md points at)
 *     scripts/       (optional — anything else the skill ships)
 * </pre>
 * The directory name is informational; the canonical skill id is the
 * {@code name:} frontmatter key. {@link SkillInfo#location()} is the path to the
 * skill's {@code SKILL.md} (a jar path for a bundled skill, a real file for a
 * player skill); {@code location.getParent()} reaches its supporting files.
 *
 * <h2>Wire format injected into the system prompt</h2>
 * {@link #formatXml()} produces, every turn, a block of the form
 * <pre>
 * Skills provide specialized instructions and workflows for specific tasks.
 * Use the load_skill tool to load a skill when a task matches its description.
 * &lt;available_skills&gt;
 *   &lt;skill&gt;
 *     &lt;name&gt;build_hut&lt;/name&gt;
 *     &lt;description&gt;Build a small 2x2 hut at the player's location.&lt;/description&gt;
 *   &lt;/skill&gt;
 * &lt;/available_skills&gt;
 * </pre>
 * Verified against opencode's {@code Skill.fmt(verbose:true)} (anomalyco/opencode
 * {@code packages/opencode/src/skill/index.ts:262-281}); descriptionless
 * skills are intentionally hidden so the model doesn't pick them blindly.
 *
 * <h2>Thread safety</h2>
 * All access from the client main thread. The backing collections are
 * non-thread-safe; {@link #declareBundled} and {@link #scan} run synchronously
 * on whatever thread the caller chooses (currently the client init thread,
 * which is the main thread before the game loop starts).
 */
public final class SkillRegistry {

    /** Required filename inside each skill directory. Matches opencode's convention. */
    public static final String SKILL_FILENAME = "SKILL.md";

    /** Sidecar file (next to the skills dir) holding the player's disabled-skill names. */
    public static final String STATE_FILENAME = "skills_state.json";

    private static final SkillRegistry INSTANCE = new SkillRegistry();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The live merged set, keyed by skill name. Insertion-ordered for stable XML output. */
    private final Map<String, SkillInfo> skills = new LinkedHashMap<>();

    /**
     * Names the player has switched off from the panel. Filtered out of both the
     * {@code <available_skills>} listing and {@link #get} (so {@code load_skill}
     * refuses a disabled skill even if the model guesses its name). Persisted to a
     * sidecar {@code skills_state.json} — a bundled skill lives inside the mod jar
     * and can't carry an {@code enabled:false} flag in its own {@code SKILL.md}, so
     * the disabled set has to live outside the skill.
     */
    private final Set<String> disabled = new LinkedHashSet<>();

    /** {@code config/numen/skills_state.json}; derived from {@code userRoot} in {@link #scan}. */
    private Path stateFile;

    /**
     * Bundled skill roots a mod has declared, in declaration order. Each is a
     * directory (typically a path inside a mod jar) holding {@code <name>/SKILL.md}
     * subdirectories. Read in place on every {@link #scan}; never copied.
     */
    private final List<Path> bundledRoots = new ArrayList<>();

    /** The player skills root ({@code config/numen/skills}); kept so {@link #rescan} can re-run. */
    private Path userRoot;

    /** Whether {@link #scan} has run at least once (so {@link #declareBundled} knows to re-merge). */
    private boolean scanned;

    private SkillRegistry() {}

    public static SkillRegistry instance() {
        return INSTANCE;
    }

    /**
     * Declare a skills root a mod ships inside its jar, so players don't have to
     * copy any files by hand. The root is a directory holding one subdirectory
     * per skill ({@code <name>/SKILL.md} plus optional supporting files); it is
     * read in place from the jar on every {@link #scan} — never copied to disk —
     * so it is always current and disappears when the mod is removed.
     *
     * <p>Call once at mod init. Resolving the jar path is loader-specific and so
     * lives in the mod, not here:
     * <pre>{@code
     * // Fabric
     * FabricLoader.getInstance().getModContainer("mymod")
     *     .flatMap(c -> c.findPath("skills"))
     *     .ifPresent(root -> SkillRegistry.instance().declareBundled(root));
     * // NeoForge
     * SkillRegistry.instance().declareBundled(
     *     ModList.get().getModFileById("mymod").getFile().findResource("skills"));
     * }</pre>
     *
     * <p>If a {@link #scan} has already happened, this re-scans so a late
     * declaration (mod init order across loaders is not guaranteed) still takes
     * effect — cheap, since skills are few.
     */
    public void declareBundled(Path skillsRoot) {
        if (skillsRoot == null) {
            Constants.LOG.warn("[numen-skill] declareBundled: null root ignored");
            return;
        }
        bundledRoots.add(skillsRoot);
        Constants.LOG.info("[numen-skill] declared bundled skills root {}", skillsRoot);
        if (scanned) rescan();
    }

    /**
     * Rebuild the live set: scan every declared bundled root (low precedence, in
     * declaration order), then the player {@code userRoot} on top (high
     * precedence, so a same-named player skill overrides a bundled one).
     *
     * @param userRoot the player skills directory ({@code config/numen/skills});
     *                 may be {@code null} or absent — then only bundled skills load
     * @return number of skills in the merged live set
     */
    public int scan(Path userRoot) {
        this.userRoot = userRoot;
        this.scanned = true;
        // Sidecar disabled-state lives next to the skills dir (config/numen/skills_state.json).
        this.stateFile = userRoot == null ? null : userRoot.resolveSibling(STATE_FILENAME);
        loadDisabled();

        Map<String, SkillInfo> next = new LinkedHashMap<>();
        int bundledCount = 0;
        for (Path root : bundledRoots) {
            bundledCount += scanInto(root, next);
        }
        scanInto(userRoot, next);

        this.skills.clear();
        this.skills.putAll(next);
        Constants.LOG.info("[numen-skill] loaded {} skill(s) ({} bundled root(s), userRoot={})",
                this.skills.size(), bundledRoots.size(), userRoot);
        return this.skills.size();
    }

    /** Re-run {@link #scan} on the last {@code userRoot}. */
    public int rescan() {
        return scan(this.userRoot);
    }

    /**
     * Walk one level deep under {@code root}, parse each {@code <sub>/SKILL.md},
     * and put it into {@code dest}. Entries already present are overridden — this
     * is how a later root (the player config) wins over an earlier one (a bundled
     * mod). A no-op when {@code root} is {@code null} or not a directory.
     *
     * @return number of skills loaded from this root
     */
    private static int scanInto(Path root, Map<String, SkillInfo> dest) {
        if (root == null || !Files.isDirectory(root)) return 0;
        int loaded = 0;
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs.sorted()::iterator) {
                if (!Files.isDirectory(dir)) continue;
                Path skillFile = dir.resolve(SKILL_FILENAME);
                if (!Files.isRegularFile(skillFile)) continue;
                try {
                    SkillInfo info = loadFile(skillFile);
                    if (info == null) continue;
                    SkillInfo prev = dest.put(info.name(), info);
                    if (prev != null) {
                        Constants.LOG.debug("[numen-skill] '{}' from {} overrides {}",
                                info.name(), info.location(), prev.location());
                    }
                    loaded++;
                } catch (IOException | RuntimeException ex) {
                    Constants.LOG.warn("[numen-skill] failed to parse {}: {}", skillFile, ex.toString());
                }
            }
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-skill] failed to list {}: {}", root, ex.toString());
        }
        return loaded;
    }

    public Optional<SkillInfo> get(String name) {
        // A disabled skill is invisible to load_skill too, not just the listing.
        if (name != null && disabled.contains(name)) return Optional.empty();
        return Optional.ofNullable(skills.get(name));
    }

    public Collection<SkillInfo> all() {
        return skills.values();
    }

    public int size() {
        return skills.size();
    }

    // ---- enable/disable (panel-facing; all on the client main thread) ----

    /** Whether {@code name} is currently switched off. */
    public boolean isDisabled(String name) {
        return disabled.contains(name);
    }

    /** Enable ({@code enabled=true}) or disable a skill by name; persists the change. */
    public void setEnabled(String name, boolean enabled) {
        if (name == null) return;
        boolean changed = enabled ? disabled.remove(name) : disabled.add(name);
        if (changed) saveDisabled();
    }

    private void loadDisabled() {
        disabled.clear();
        if (stateFile == null || !Files.isRegularFile(stateFile)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (o.has("disabled") && o.get("disabled").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("disabled")) {
                    if (!el.isJsonNull()) disabled.add(el.getAsString());
                }
            }
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-skill] unreadable {}: {}", stateFile, ex.toString());
        }
    }

    private void saveDisabled() {
        if (stateFile == null) return;
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        disabled.forEach(arr::add);
        o.add("disabled", arr);
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-skill] failed to write {}: {}", stateFile, ex.toString());
        }
    }

    /**
     * Build the XML block that gets appended to the system prompt every turn.
     * Returns the empty string when there are no described skills, so callers
     * can unconditionally append it.
     *
     * <p>Format reproduces opencode {@code Skill.fmt(verbose:true)} verbatim,
     * minus the {@code <location>} child (which opencode uses for IDE display
     * and the LLM doesn't need).
     */
    public String formatXml() {
        // Mirror opencode: only show skills with a description (the LLM can't
        // pick something it knows nothing about). Skills the player switched off
        // in the panel are hidden here too, so the brain stops loading them.
        var described = skills.values().stream()
                .filter(s -> s.description() != null && !s.description().isBlank())
                .filter(s -> !disabled.contains(s.name()))
                .toList();
        if (described.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(256);
        sb.append("Skills provide specialized instructions and workflows for specific tasks.\n");
        sb.append("Use the load_skill tool to load a skill when a task matches its description.\n");
        sb.append("<available_skills>\n");
        for (SkillInfo s : described) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(escapeXml(s.name())).append("</name>\n");
            sb.append("    <description>").append(escapeXml(s.description())).append("</description>\n");
            sb.append("  </skill>\n");
        }
        sb.append("</available_skills>");
        return sb.toString();
    }

    private static SkillInfo loadFile(Path skillFile) throws IOException {
        SkillInfo info = parse(Files.readString(skillFile), skillFile.toAbsolutePath());
        if (info == null) {
            Constants.LOG.warn("[numen-skill] {} missing required 'name:' frontmatter; skipping",
                    skillFile);
        }
        return info;
    }

    /** 附属文件尺寸上限(角色是"给模型看的文本",不是数据仓)。 */
    private static final long SUPPORT_FILE_MAX_BYTES = 256 * 1024;

    /**
     * 三级披露:读取技能目录内的一个附属文件(SKILL.md 正文以相对路径引用,
     * 模型按需拉取)。路径规范化后必须仍在技能目录内——拒绝任何形式的穿越。
     *
     * @return 文件文本;技能不存在、路径越界、文件缺失或超限时抛 IllegalArgumentException
     */
    public String readSupportFile(String skillName, String relPath) {
        SkillInfo info = get(skillName).orElseThrow(() ->
                new IllegalArgumentException("unknown skill: " + skillName));
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("file is required");
        }
        Path dir = info.location().getParent();
        Path target = dir.resolve(relPath).normalize();
        if (!target.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("file must stay inside the skill directory");
        }
        try {
            if (!Files.isRegularFile(target)) {
                throw new IllegalArgumentException("no such file in skill " + skillName + ": " + relPath
                        + "; available: " + String.join(", ", listSupportFiles(skillName)));
            }
            if (Files.size(target) > SUPPORT_FILE_MAX_BYTES) {
                throw new IllegalArgumentException(relPath + " exceeds the "
                        + (SUPPORT_FILE_MAX_BYTES / 1024) + "KB support-file cap");
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + relPath + ": " + e.getMessage(), e);
        }
    }

    /** 技能目录内可引用的附属文件相对路径清单(不含 SKILL.md 本身,字典序)。 */
    public List<String> listSupportFiles(String skillName) {
        SkillInfo info = get(skillName).orElseThrow(() ->
                new IllegalArgumentException("unknown skill: " + skillName));
        Path dir = info.location().getParent();
        List<String> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().equals("SKILL.md"))
                    .forEach(f -> out.add(dir.relativize(f).toString().replace('\\', '/')));
        } catch (IOException e) {
            Constants.LOG.warn("[numen-skill] cannot list support files of {}", skillName, e);
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Parse {@code SKILL.md} text into a {@link SkillInfo}, or {@code null} if it
     * lacks a {@code name:} frontmatter key.
     */
    private static SkillInfo parse(String raw, Path location) {
        SkillMarkdown.Parsed parsed = SkillMarkdown.parse(raw);
        String name = parsed.frontmatter().get("name");
        if (name == null || name.isBlank()) return null;
        String description = parsed.frontmatter().get("description");
        if (description != null && description.isBlank()) description = null;
        return new SkillInfo(name.trim(), description, parsed.content(), location);
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
