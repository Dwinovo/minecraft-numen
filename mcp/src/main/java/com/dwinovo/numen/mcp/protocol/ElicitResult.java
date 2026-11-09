package com.dwinovo.numen.mcp.protocol;

import com.google.gson.JsonObject;

/**
 * 人对一张表单的表态。
 *
 * @param content 选了 {@link Action#ACCEPT} 时填的内容;别的是 null
 */
public record ElicitResult(Action action, JsonObject content) {

    /** 填了交回、明确拒绝、关掉没答。 */
    public enum Action { ACCEPT, DECLINE, CANCEL }
}
