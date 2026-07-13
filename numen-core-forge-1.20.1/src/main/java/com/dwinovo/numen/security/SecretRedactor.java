package com.dwinovo.numen.security;

import java.util.regex.Pattern;

/** Central redaction for logs, diagnostics and exported settings. */
public final class SecretRedactor {
    private static final Pattern HEADER = Pattern.compile(
            "(?im)^((?:authorization|proxy-authorization|api[-_ ]?key|cookie|set-cookie)\\s*[:=]\\s*)[^\\r\\n]+$");
    private static final Pattern JSON = Pattern.compile(
            "(?i)([\\\"'](?:authorization|proxy[-_]?authorization|api[-_]?key|token|access[-_]?token|secret|password|signature|cookie)[\\\"']\\s*[:=]\\s*[\\\"'])[^\\\"']*([\\\"'])");
    private static final Pattern QUERY = Pattern.compile(
            "(?i)([?&](?:api[-_]?key|key|token|access[-_]?token|secret|password|signature)=)[^&#\\s]*");
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)(https?://)[^/@\\s]+@");
    private static final Pattern BEARER = Pattern.compile("(?i)(\\bbearer\\s+)[a-z0-9._~+/-]+=*");
    private static final Pattern COMMON_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{8,}\\b");

    private SecretRedactor() { }

    public static String redact(String input) { return redact(input, null); }

    public static String redact(String input, String knownSecret) {
        String value = input == null ? "" : input;
        if (knownSecret != null && !knownSecret.isBlank()) value = value.replace(knownSecret, "<redacted>");
        value = HEADER.matcher(value).replaceAll("$1<redacted>");
        value = JSON.matcher(value).replaceAll("$1<redacted>$2");
        value = QUERY.matcher(value).replaceAll("$1<redacted>");
        value = URL_USERINFO.matcher(value).replaceAll("$1<redacted>@");
        value = BEARER.matcher(value).replaceAll("$1<redacted>");
        return COMMON_KEY.matcher(value).replaceAll("<redacted>");
    }
}
