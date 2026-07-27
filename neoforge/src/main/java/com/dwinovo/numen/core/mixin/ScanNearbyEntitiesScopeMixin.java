package com.dwinovo.numen.core.mixin;

import com.google.gson.JsonObject;
import com.dwinovo.numen.core.combat.CombatEntityScanner;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.tools.ScanNearbyEntitiesTool")
public abstract class ScanNearbyEntitiesScopeMixin {
    @Inject(method = "description", at = @At("RETURN"), cancellable = true)
    private void numen$describeBoundedArea(CallbackInfoReturnable<String> callback) {
        callback.setReturnValue(callback.getReturnValue()
            + " Nearby means a fixed horizontal circle with a bounded Minecraft Y range."
            + " origin defaults to self; level_scope defaults to same_plane.");
    }

    @Inject(method = "parameterSchema", at = @At("RETURN"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void numen$addAreaParameters(CallbackInfoReturnable<Map<String, Object>> callback) {
        Map<String, Object> schema = new LinkedHashMap<>(callback.getReturnValue());
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
        properties.put("origin", enumProperty(
            "Center of the fixed scan area. self is the AI and is the default; owner requires an explicit user request.",
            List.of("self", "owner")
        ));
        properties.put("level_scope", enumProperty(
            "same_plane is the default fixed Y band (+/-2 blocks); all uses the scan radius above and below.",
            List.of("same_plane", "all")
        ));
        schema.put("properties", properties);
        callback.setReturnValue(schema);
    }

    @Inject(method = "onServerCall", at = @At("HEAD"), cancellable = true)
    private void numen$scanFixedCombatArea(
        String callId,
        JsonObject args,
        NumenPlayer player,
        Consumer<String> reply,
        CallbackInfo callback
    ) {
        try {
            double radius = args.has("radius") ? args.get("radius").getAsDouble() : 16.0;
            String filter = text(args, "type_filter", "hostile");
            String origin = text(args, "origin", "self");
            String levelScope = text(args, "level_scope", "same_plane");
            reply.accept(CombatEntityScanner.scan(radius, filter, origin, levelScope, player));
        } catch (RuntimeException error) {
            JsonObject result = new JsonObject();
            result.addProperty("error", error.getMessage());
            reply.accept(result.toString());
        }
        callback.cancel();
    }

    private static Map<String, Object> enumProperty(String description, List<String> values) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        property.put("enum", values);
        return property;
    }

    private static String text(JsonObject args, String name, String fallback) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsString() : fallback;
    }
}
