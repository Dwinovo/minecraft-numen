package com.dwinovo.numen.agent.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/** Platform-independent schema validation for the user-editable model registry. */
final class ModelRegistryJson {
    private ModelRegistryJson() { }

    static JsonObject validate(JsonElement value) {
        JsonObject root = value.getAsJsonObject();
        if (!root.has("providers") || !root.get("providers").isJsonArray()
                || root.getAsJsonArray("providers").isEmpty()) {
            throw new IllegalArgumentException("models registry has no providers");
        }
        Set<String> providerIds = new HashSet<>();
        for (JsonElement providerElement : root.getAsJsonArray("providers")) {
            JsonObject provider = providerElement.getAsJsonObject();
            String providerId = requiredString(provider, "id");
            requiredString(provider, "name");
            if (!providerIds.add(providerId)) {
                throw new IllegalArgumentException("duplicate provider id: " + providerId);
            }
            if (provider.has("headers") && !provider.get("headers").isJsonObject()) {
                throw new IllegalArgumentException("headers is not an object for " + providerId);
            }
            if (provider.has("models")) {
                if (!provider.get("models").isJsonArray()) {
                    throw new IllegalArgumentException("models is not an array for " + providerId);
                }
                Set<String> modelIds = new HashSet<>();
                for (JsonElement modelElement : provider.getAsJsonArray("models")) {
                    JsonObject model = modelElement.getAsJsonObject();
                    String modelId = requiredString(model, "id");
                    if (!modelIds.add(modelId)) {
                        throw new IllegalArgumentException("duplicate model id for " + providerId + ": " + modelId);
                    }
                    if (model.has("ctx") && model.get("ctx").getAsInt() <= 0) {
                        throw new IllegalArgumentException("invalid context window for " + providerId);
                    }
                }
            }
        }
        return root;
    }

    static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing " + key);
        }
        String value = object.get(key).getAsString();
        if (value.isBlank()) throw new IllegalArgumentException("blank " + key);
        return value;
    }
}
