package com.dwinovo.numen.mcp.client.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器在 401/403 上用 {@code WWW-Authenticate: Bearer …} 说的话:受保护资源元数据在哪({@code resource_metadata})、
 * 需要哪些权限({@code scope})、哪里不对({@code error},如 {@code insufficient_scope})。都可能没有。
 */
public record Challenge(String resourceMetadata, List<String> scope, String error) {

    public static final Challenge NONE = new Challenge(null, List.of(), null);

    public boolean insufficientScope() {
        return "insufficient_scope".equals(error);
    }

    /** 从响应的 {@code WWW-Authenticate} 头(可能有几条)里取 Bearer 那条的参数;没有 Bearer 的就是 {@link #NONE}。 */
    public static Challenge parse(List<String> headers) {
        for (String header : headers) {
            String trimmed = header.strip();
            if (trimmed.regionMatches(true, 0, "Bearer", 0, 6)) {
                Map<String, String> params = params(trimmed.substring(6));
                String scope = params.get("scope");
                return new Challenge(params.get("resource_metadata"),
                        scope == null || scope.isBlank() ? List.of() : List.of(scope.strip().split("\\s+")),
                        params.get("error"));
            }
        }
        return NONE;
    }

    /** {@code key=value, key="quoted value"} 的列表;引号里可以有逗号。 */
    private static Map<String, String> params(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == ',')) i++;
            int eq = text.indexOf('=', i);
            if (eq < 0) break;
            String key = text.substring(i, eq).strip().toLowerCase();
            i = eq + 1;
            while (i < n && text.charAt(i) == ' ') i++;
            StringBuilder value = new StringBuilder();
            if (i < n && text.charAt(i) == '"') {
                i++;
                while (i < n && text.charAt(i) != '"') {
                    if (text.charAt(i) == '\\' && i + 1 < n) i++;
                    value.append(text.charAt(i++));
                }
                i++;   // 收尾的引号
            } else {
                while (i < n && text.charAt(i) != ',') value.append(text.charAt(i++));
            }
            out.put(key, value.toString().strip());
        }
        return out;
    }

    /** 多个权限去重合并,保持先后。 */
    public static List<String> merge(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        for (String s : b) {
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }
}
