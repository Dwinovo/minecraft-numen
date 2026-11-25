package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.mcp.rpc.RpcError;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.UUID;

/** 座位上消息的参数怎么读:对端发来的都不可信,读不成一律是 {@link RpcError#INVALID_PARAMS}。两端共用。 */
final class Params {

    private Params() {}

    static UUID uuid(JsonObject params, String name) throws RpcException {
        String text = string(params, name);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notUuid) {
            throw invalid(name, "a UUID");
        }
    }

    /** 非空白的文字。 */
    static String string(JsonObject params, String name) throws RpcException {
        String text = optionalString(params, name);
        if (text == null || text.isBlank()) {
            throw invalid(name, "a non-blank string");
        }
        return text;
    }

    /** 可以不写的文字;写了就得是文字。 */
    static String optionalString(JsonObject params, String name) throws RpcException {
        JsonElement value = params.get(name);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value instanceof JsonPrimitive p && p.isString()) {
            return p.getAsString();
        }
        throw invalid(name, "a string");
    }

    /** 整数;没有时是 {@code absent}。 */
    static long integer(JsonObject params, String name, long absent) throws RpcException {
        JsonElement value = params.get(name);
        if (value == null || value.isJsonNull()) {
            return absent;
        }
        if (value instanceof JsonPrimitive p && p.isNumber()) {
            try {
                return p.getAsBigDecimal().longValueExact();
            } catch (ArithmeticException notWhole) {
                throw invalid(name, "an integer");
            }
        }
        throw invalid(name, "an integer");
    }

    /** 必须写的整数。 */
    static long requiredInteger(JsonObject params, String name) throws RpcException {
        if (params.get(name) == null || params.get(name).isJsonNull()) {
            throw invalid(name, "an integer");
        }
        return integer(params, name, 0);
    }

    static boolean bool(JsonObject params, String name, boolean absent) throws RpcException {
        JsonElement value = params.get(name);
        if (value == null || value.isJsonNull()) {
            return absent;
        }
        if (value instanceof JsonPrimitive p && p.isBoolean()) {
            return p.getAsBoolean();
        }
        throw invalid(name, "a boolean");
    }

    static JsonObject object(JsonObject params, String name) throws RpcException {
        if (params.get(name) instanceof JsonObject o) {
            return o;
        }
        throw invalid(name, "an object");
    }

    private static RpcException invalid(String name, String expected) {
        return new RpcException(RpcError.INVALID_PARAMS, "params." + name + " must be " + expected);
    }
}
