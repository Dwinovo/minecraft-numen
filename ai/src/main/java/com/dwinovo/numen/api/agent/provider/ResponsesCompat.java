package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.Api;

/**
 * Responses 的兼容项。
 *
 * @param developerRole 系统提示用 {@code developer} 角色(推理型号认它),还是 {@code system}
 */
public record ResponsesCompat(boolean developerRole) implements Compat {

    @Override
    public Api api() {
        return Api.RESPONSES;
    }
}
