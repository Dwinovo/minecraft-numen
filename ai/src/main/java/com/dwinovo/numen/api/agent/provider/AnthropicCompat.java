package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.Api;

/**
 * Anthropic Messages 的兼容项。
 *
 * @param adaptiveThinking 思考用 {@code thinking.type:adaptive} + {@code output_config.effort}(新型号),
 *                         还是 {@code thinking.type:enabled} + {@code budget_tokens}(老型号)
 * @param temperature      这个端点收不收 {@code temperature}
 */
public record AnthropicCompat(boolean adaptiveThinking, boolean temperature) implements Compat {

    @Override
    public Api api() {
        return Api.ANTHROPIC;
    }
}
