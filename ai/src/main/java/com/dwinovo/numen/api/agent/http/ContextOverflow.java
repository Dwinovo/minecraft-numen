package com.dwinovo.numen.api.agent.http;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 服务商说"上下文超长"的那些话。各家的措辞不一(照 pi 的 {@code isContextOverflow} 收的那一份):
 * 能从厂家的原话里认出来,就不是"请求有问题"而是"该整理记忆再试一次"。
 * 限流、服务端出错这类也可能带着相近的字眼(如 "Too many tokens, please wait"),先排除再认。
 */
public final class ContextOverflow {

    private ContextOverflow() {}

    private static final List<Pattern> OVERFLOW = List.of(
            p("prompt (?:is )?too long"),                                   // Anthropic、z.ai
            p("prompt exceeds max length"),                                  // z.ai 国内站
            p("request_too_large"),                                          // Anthropic 请求体过大
            p("input is too long for requested model"),                      // Bedrock
            p("exceeds the context window"),                                 // OpenAI
            p("exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))"), // 兼容代理
            p("context window exceeded"),                                    // LiteLLM
            p("input token count.*exceeds the maximum"),                     // Gemini
            p("maximum prompt length is \\d+"),                              // xAI
            p("reduce the length of the messages"),                          // Groq
            p("maximum context length is \\d+ tokens"),                      // OpenRouter
            p("exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?"),
            p("input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)"), // Together
            p("exceeds the limit of \\d+"),                                  // GitHub Copilot
            p("exceeds the available context size"),                         // llama.cpp
            p("greater than the context length"),                            // LM Studio
            p("context window exceeds limit"),                               // MiniMax
            p("exceeded model token limit"),                                 // Kimi
            p("too large for model with \\d+ maximum context length"),       // Mistral
            p("prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?"),
            p("model_context_window_exceeded"),                              // z.ai
            p("prompt too long; exceeded (?:max )?context length"),          // Ollama
            p("range of input length should be"),                            // DashScope / Qwen
            p("context[_ ]length[_ ]exceeded"),
            p("too many tokens"),
            p("token limit exceeded"));

    /** 这些是限流与服务端的问题,即使字眼像超长也不算。 */
    private static final List<Pattern> NOT_OVERFLOW = List.of(
            p("^(?:throttling error|service unavailable):"),
            p("rate limit"),
            p("too many requests"));

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /** 这次失败是不是服务商在说上下文超长:HTTP 413 一律算,其余看厂家的原话。 */
    public static boolean is(LlmHttpException failure) {
        if (failure.statusCode() == 413) {
            return true;
        }
        return is(failure.providerMessage());
    }

    /** 厂家的这句话是不是在说上下文超长。 */
    public static boolean is(String providerMessage) {
        if (providerMessage == null || providerMessage.isBlank()) {
            return false;
        }
        for (Pattern notOverflow : NOT_OVERFLOW) {
            if (notOverflow.matcher(providerMessage).find()) {
                return false;
            }
        }
        for (Pattern overflow : OVERFLOW) {
            if (overflow.matcher(providerMessage).find()) {
                return true;
            }
        }
        return false;
    }
}
