package com.dwinovo.numen.mcp.protocol;

import com.google.gson.JsonObject;

/** 服务端要客户端问人的一张表单({@code elicitation/create},表单模式)。 */
public record ElicitRequest(String message, JsonObject requestedSchema) {
}
