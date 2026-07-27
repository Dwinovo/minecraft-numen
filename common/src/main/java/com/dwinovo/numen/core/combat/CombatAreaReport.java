package com.dwinovo.numen.core.combat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class CombatAreaReport {
    private CombatAreaReport() {
    }

    public static Map<String, Object> describe(CombatArea area) {
        Map<String, Object> center = new LinkedHashMap<>();
        center.put("x", area.centerX());
        center.put("y", area.centerY());
        center.put("z", area.centerZ());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("origin", area.origin().name().toLowerCase(java.util.Locale.ROOT));
        result.put("level_scope", area.levelScope().name().toLowerCase(java.util.Locale.ROOT));
        result.put("horizontal_radius", area.horizontalRadius());
        result.put("vertical_range", area.verticalRange());
        result.put("vertical_axis", "Y");
        result.put("center", center);
        return result;
    }

    public static String appendSkipped(String message, Set<Integer> outOfScope) {
        if (outOfScope.isEmpty()) {
            return message;
        }
        return message + "; skipped out-of-scope entity ids " + outOfScope;
    }
}
