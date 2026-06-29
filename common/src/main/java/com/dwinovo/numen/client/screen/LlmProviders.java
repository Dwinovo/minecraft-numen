package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.model.ModelRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * The LLM providers offered in the settings UI — derived from the {@link ModelRegistry} (bundled
 * {@code numen_models.json}) so adding a provider/model is a data edit, not code. Shared by the
 * standalone {@link SettingsScreen} and the {@link NumenScreen} Settings tab.
 */
public final class LlmProviders {

    /** Every provider, live from the registry (so a newly-added site shows up immediately). */
    public static List<Option> all() {
        List<Option> out = new ArrayList<>();
        for (ModelRegistry.Provider p : ModelRegistry.providers()) {
            String defaultModel = p.models().isEmpty() ? "" : p.models().get(0).id();
            out.add(new Option(p.id(), p.name(), defaultModel, p.baseUrl()));
        }
        return List.copyOf(out);
    }

    private LlmProviders() {}

    public record Option(String id, String displayName, String defaultModel, String defaultBaseUrl) {}

    public static Option byId(String id) {
        String norm = normalize(id);
        List<Option> all = all();
        for (Option o : all) {
            if (o.id().equals(norm)) return o;
        }
        return all.isEmpty() ? new Option("openai", "OpenAI", "", "") : all.get(0);
    }

    /** Map config aliases (kimi/doubao/qwen/…) onto canonical provider ids. */
    public static String normalize(String raw) {
        if (raw == null) return "openai";
        return switch (raw.toLowerCase()) {
            case "kimi" -> "moonshot";
            case "doubao", "ark" -> "volcengine";
            case "qwen", "tongyi", "aliyun" -> "dashscope";
            case "glm" -> "zhipu";
            case "silicon" -> "siliconflow";
            default -> raw.toLowerCase();
        };
    }
}
