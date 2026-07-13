package com.dwinovo.numen.client.diagnostic;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.http.LlmHttpException;
import com.dwinovo.numen.agent.llm.LlmEndpointDiagnostics;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.model.ModelRegistry;
import com.dwinovo.numen.agent.provider.LlmProvider;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import com.dwinovo.numen.security.SecretRedactor;

/** Writes redacted, standalone diagnostics for failures triggered from the AI settings screen. */
public final class AiFailureReporter {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final int MAX_SECTION_CHARS = 16_384;
    private static final Pattern BEARER = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+", Pattern.MULTILINE);
    private static final Pattern JSON_KEY = Pattern.compile("(?i)([\\\"'](?:api[_-]?key|token|authorization)[\\\"']\\s*[:=]\\s*[\\\"'])[^\\\"']+([\\\"'])");
    private static final Pattern SK_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{8,}\\b");
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:api[_-]?key|key|token|access[_-]?token)=)[^&\\s]+"
    );
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^/@\\s]+@");

    private AiFailureReporter() { }

    public static Path write(String operation, LlmEndpointDiagnostics.Settings settings,
                             String userMessage, Throwable error) {
        try {
            Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("aifailure");
            Files.createDirectories(directory);
            String safeOperation = operation == null ? "unknown" : operation.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path file = directory.resolve(FILE_TIME.format(java.time.LocalDateTime.now()) + "-"
                    + safeOperation + "-" + UUID.randomUUID().toString().substring(0, 8) + ".txt");
            String report = buildReport(operation, settings, userMessage, error);
            Files.writeString(file, report, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Constants.LOG.warn("[numen-ai-diagnostic] failure report written to {}", file.toAbsolutePath());
            return file;
        } catch (Exception writeError) {
            Constants.LOG.error("[numen-ai-diagnostic] unable to write failure report", writeError);
            return null;
        }
    }

    private static String buildReport(String operation, LlmEndpointDiagnostics.Settings settings,
                                      String userMessage, Throwable error) {
        String key = settings == null ? "" : nullToEmpty(settings.apiKey());
        StringBuilder out = new StringBuilder(2048);
        line(out, "Numen AI failure diagnostic");
        line(out, "generated_at", OffsetDateTime.now().toString());
        line(out, "operation", nullToEmpty(operation));
        line(out, "failure_reason", sanitize(userMessage, key));
        line(out, "minecraft_version", SharedConstants.getCurrentVersion().getName());
        line(out, "java_version", System.getProperty("java.version", "unknown"));
        line(out, "os", System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", ""));
        if (settings != null) {
            line(out, "provider", sanitize(settings.providerId(), key));
            line(out, "model", sanitize(settings.model(), key));
            line(out, "base_url", sanitize(settings.baseUrl(), key));
            line(out, "full_url", Boolean.toString(settings.fullUrl()));
            line(out, "effective_url", sanitize(effectiveUrl(operation, settings), key));
            line(out, "http_proxy", settings.proxy() == null || settings.proxy().isBlank()
                    ? "not configured" : sanitize(settings.proxy(), key));
            line(out, "api_key", key.isBlank() ? "not configured" : "configured (redacted, length=" + key.length() + ")");
        }

        Throwable cause = unwrap(error);
        Diagnosis diagnosis = diagnose(cause);
        line(out, "diagnosis", diagnosis.summary());
        line(out, "recommended_action", diagnosis.action());
        if (cause instanceof LlmHttpException http) {
            line(out, "http_status", Integer.toString(http.statusCode()));
            section(out, "http_response_body", sanitize(http.responseBody(), key));
        }
        if (error != null) {
            line(out, "root_exception", cause.getClass().getName());
            line(out, "root_message", sanitize(cause.getMessage(), key));
            section(out, "exception_chain", sanitize(exceptionChain(error), key));
            section(out, "stack_trace", sanitize(stackTrace(error), key));
        }
        return out.toString();
    }

    private static Diagnosis diagnose(Throwable cause) {
        if (cause instanceof LlmHttpException http) {
            if (http.statusCode() == 401 || http.statusCode() == 403) {
                return new Diagnosis("中转站拒绝了身份验证或模型权限。",
                        "检查 API Key 是否正确、是否过期，以及账号是否有当前模型的使用权限。");
            }
            if (http.statusCode() == 404 || http.statusCode() == 405) {
                return new Diagnosis("请求地址或 HTTP 方法不被中转站支持。",
                        "核对 effective_url；普通模式检查接口路径，完整 URL 模式确认该地址接受当前操作。");
            }
            if (http.statusCode() == 429) {
                return new Diagnosis("请求被限流，或账号余额/额度不足。",
                        "检查中转站余额和速率限制，稍后重试或降低请求频率。");
            }
            if (http.statusCode() >= 500) {
                return new Diagnosis("中转站或其上游模型服务发生服务器错误。",
                        "稍后重试，并将本文件中的 HTTP 状态和响应正文提供给中转站客服。");
            }
            return new Diagnosis("中转站返回 HTTP " + http.statusCode() + "，请求未被接受。",
                    "查看 http_response_body，检查模型 ID、请求地址以及中转站的 OpenAI 兼容要求。");
        }
        if (cause instanceof UnknownHostException) {
            return new Diagnosis("Base URL 的域名无法通过 DNS 解析。",
                    "检查域名拼写、网络 DNS 和本机代理设置。");
        }
        if (cause instanceof ConnectException) {
            return new Diagnosis("无法与目标服务器或配置的 HTTP 代理建立连接。",
                    "检查目标地址、端口、网络防火墙以及 host:port 代理配置。");
        }
        if (cause instanceof HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
            return new Diagnosis("中转站在超时时间内没有完成响应。",
                    "检查网络和代理，稍后重试；持续发生时联系中转站确认服务状态。");
        }
        if (cause instanceof IllegalArgumentException) {
            return new Diagnosis("设置无效，或中转站响应不是预期的 OpenAI 兼容 JSON 格式。",
                    "检查 Base URL、完整 URL 开关、模型 ID，并查看 root_message 和响应正文。");
        }
        return new Diagnosis("发生未分类的客户端、TLS、网络或响应处理异常。",
                "查看 exception_chain 和 stack_trace；提交问题时附上此文件，但不要另行提供 API Key。");
    }

    private static String effectiveUrl(String operation, LlmEndpointDiagnostics.Settings settings) {
        try {
            if (settings.fullUrl()) return nullToEmpty(settings.baseUrl()).trim();
            String providerId = settings.providerId() == null || settings.providerId().isBlank()
                    ? "openai" : settings.providerId();
            LlmProvider provider = NumenLlmClient.pickProvider(providerId);
            String base = NumenLlmClient.composeBaseUrl(settings.baseUrl(),
                    ModelRegistry.baseUrl(providerId), provider);
            return base + ("model_detection".equals(operation) ? "/models" : "/chat/completions");
        } catch (RuntimeException error) {
            return nullToEmpty(settings.baseUrl());
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause != null && cause.getCause() != null
                && (cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        return cause == null ? new IllegalStateException("unknown failure") : cause;
    }

    private static String exceptionChain(Throwable error) {
        StringBuilder chain = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (!chain.isEmpty()) chain.append("\ncaused by: ");
            chain.append(current.getClass().getName()).append(": ").append(nullToEmpty(current.getMessage()));
            current = current.getCause();
        }
        return chain.toString();
    }

    private static String stackTrace(Throwable error) {
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    private static String sanitize(String text, String apiKey) {
        return SecretRedactor.redact(text, apiKey);
    }

    private static void line(StringBuilder out, String value) {
        out.append(value).append('\n');
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append(": ").append(nullToEmpty(value).replace('\r', ' ').replace('\n', ' ')).append('\n');
    }

    private static void section(StringBuilder out, String name, String value) {
        String content = nullToEmpty(value);
        if (content.length() > MAX_SECTION_CHARS) {
            content = content.substring(0, MAX_SECTION_CHARS) + "\n... truncated ...";
        }
        out.append('\n').append('[').append(name.toLowerCase(Locale.ROOT)).append("]\n")
                .append(content).append('\n');
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Diagnosis(String summary, String action) { }
}
