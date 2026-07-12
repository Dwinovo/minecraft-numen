package com.dwinovo.numen.persona;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's library of reusable personas, at {@code config/numen/personas.json} — the authoring
 * surface behind the panel's "人设" tab. A persona is a short "who you are" text; assigning one to a
 * companion copies its {@code text} into that companion (via {@code EntityAgentLoop.setPersona}), so
 * later library edits don't retroactively mutate a live companion.
 *
 * <p>Read-only <b>presets</b> ({@code preset:true}) are regenerated from {@link #defaults()} on every
 * load (so they can't be corrupted or lost); the file only persists user-created personas. Mirrors the
 * degrade-on-unreadable, pretty-Gson style of {@code McpClientConfig}. Client-side singleton.
 */
public final class PersonaLibrary {

    /** One persona template. {@code preset} personas are built-in and immutable (clone to customize). */
    public record Persona(String id, String name, String text, boolean preset) {}

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static PersonaLibrary instance;

    private final Path file;
    private final Map<String, Persona> personas = new LinkedHashMap<>();

    private PersonaLibrary(Path file) {
        this.file = file;
    }

    public static PersonaLibrary instance() {
        if (instance == null) {
            Path dir = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("numen");
            instance = new PersonaLibrary(dir.resolve("personas.json"));
            instance.load();
        }
        return instance;
    }

    /** All personas (presets first, then user-created), in library order. */
    public List<Persona> list() {
        return new ArrayList<>(personas.values());
    }

    public Persona get(String id) {
        return personas.get(id);
    }

    /** Create a new user persona and persist. */
    public Persona create(String name, String text) {
        String id = "p_" + Long.toHexString(System.currentTimeMillis()) + "_" + personas.size();
        Persona p = new Persona(id, name, text, false);
        personas.put(id, p);
        save();
        return p;
    }

    /** Edit a user persona (no-op on presets). */
    public void update(String id, String name, String text) {
        Persona old = personas.get(id);
        if (old == null || old.preset()) return;
        personas.put(id, new Persona(id, name, text, false));
        save();
    }

    /** Delete a user persona (no-op on presets). */
    public void remove(String id) {
        Persona p = personas.get(id);
        if (p != null && !p.preset()) {
            personas.remove(id);
            save();
        }
    }

    /** Clone any persona (incl. a preset) into a new editable user copy. */
    public Persona clonePersona(String id) {
        Persona src = personas.get(id);
        if (src == null) return null;
        return create(src.name() + " 副本", src.text());
    }

    // ---- pending summon assignment ----
    // The persona picked at summon time, keyed by the companion name — the client doesn't know the new
    // companion's UUID until the roster snapshot arrives, so it's resolved there (CompanionListPayload).

    private static final Map<String, String> PENDING_SUMMON = new LinkedHashMap<>();

    /** Remember the persona chosen for a companion being summoned (by name). {@code personaId} null = default. */
    public static void pendSummon(String name, String personaId) {
        if (name == null || personaId == null) return;
        PENDING_SUMMON.put(name, personaId);
    }

    /** Take (and clear) the persona pending for a just-arrived companion name, or null if none/unknown. */
    public static Persona takePendingSummon(String name) {
        String id = PENDING_SUMMON.remove(name);
        return id == null ? null : instance().get(id);
    }

    // ---- persistence ----

    private void load() {
        personas.clear();
        for (Persona p : defaults()) personas.put(p.id(), p);   // presets always fresh, never from file
        if (!Files.isRegularFile(file)) {
            save();   // seed the file on first launch
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("personas") && o.get("personas").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("personas")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject po = el.getAsJsonObject();
                    if (po.has("preset") && po.get("preset").getAsBoolean()) continue;   // presets from defaults()
                    String id = str(po, "id");
                    if (id.isEmpty()) continue;
                    personas.put(id, new Persona(id, str(po, "name"), str(po, "text"), false));
                }
            }
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-persona] unreadable {} — using presets only: {}", file, ex.toString());
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Persona p : personas.values()) {
            JsonObject po = new JsonObject();
            po.addProperty("id", p.id());
            po.addProperty("name", p.name());
            po.addProperty("text", p.text());
            po.addProperty("preset", p.preset());
            arr.add(po);
        }
        root.add("personas", arr);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-persona] failed to write {}: {}", file, ex.toString());
        }
    }

    /** Built-in read-only presets. Persona defines personality only — the operating core is fixed elsewhere. */
    private static List<Persona> defaults() {
        return List.of(
                new Persona("preset_lively", "活泼助手",
                        "你性格开朗、热情、乐于助人，说话简短有活力。", true),
                new Persona("preset_steady", "沉稳向导",
                        "你沉着老练、经验丰富，说话简洁可靠，像个可信赖的向导。", true),
                new Persona("preset_swordsman", "毒舌剑客",
                        "你是个高冷毒舌但靠谱的战斗型伙伴，话不多、偏冷，关键时刻绝对可靠。", true),
                new Persona("preset_scholar", "话痨学者",
                        "你博学好奇、话略多，喜欢解释来龙去脉，但不会啰嗦到误事。", true));
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }
}
