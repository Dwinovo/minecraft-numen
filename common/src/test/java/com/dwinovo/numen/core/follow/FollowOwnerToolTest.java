package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class FollowOwnerToolTest {

    private static final FollowConfig CONFIG = FollowConfig.defaults();

    @Test
    void toolNameIsExact() {
        assertEquals("follow_owner", new FollowOwnerTool(CONFIG).name());
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaAcceptsOnlyOneRequiredFiveValueAction() {
        Map<String, Object> schema =
                new FollowOwnerTool(CONFIG).parameterSchema();
        Map<String, Object> properties =
                (Map<String, Object>) schema.get("properties");
        Map<String, Object> action =
                (Map<String, Object>) properties.get("action");

        assertEquals(List.of("action"), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(1, properties.size());
        assertEquals("string", action.get("type"));
        assertEquals(List.of("on", "off", "pause", "resume", "status"),
                action.get("enum"));
    }

    @Test
    void descriptionDistinguishesPersistentFollowFromGotoAndTeleport() {
        String description = new FollowOwnerTool(CONFIG).description();

        assertTrue(description.contains("跟着我"));
        assertTrue(description.contains("以后别跟了"));
        assertTrue(description.contains("先在这里等一下"));
        assertTrue(description.contains("继续跟我"));
        assertTrue(description.contains("你为什么不动"));
        assertTrue(description.contains("goto"));
        assertTrue(description.contains("去某坐标"));
        assertTrue(description.contains("does not teleport"));
        assertTrue(description.contains("OwnerFollowChain"));
    }

    @ParameterizedTest
    @EnumSource(FollowAction.class)
    void parserAcceptsEveryActionCaseInsensitively(FollowAction action) {
        JsonObject args = new JsonObject();
        args.addProperty("action", action.name());

        assertEquals(action, FollowOwnerTool.parseArguments(args));
    }

    @Test
    void unknownActionIsRejectedBeforeAnyControlCall() {
        JsonObject args = new JsonObject();
        args.addProperty("action", "goto");

        assertThrows(IllegalArgumentException.class,
                () -> FollowOwnerTool.parseArguments(args));
    }

    @Test
    void missingOrNonStringActionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FollowOwnerTool.parseArguments(new JsonObject()));
        JsonObject numeric = new JsonObject();
        numeric.addProperty("action", 1);
        assertThrows(IllegalArgumentException.class,
                () -> FollowOwnerTool.parseArguments(numeric));
    }

    @Test
    void companionNameUuidDistanceAndConfigArgumentsAreRejected() {
        for (String forbidden : List.of(
                "companion_name", "companion_uuid", "owner_uuid",
                "distance", "config", "teleport", "allow_break")) {
            JsonObject args = new JsonObject();
            args.addProperty("action", "status");
            args.addProperty(forbidden, "forbidden");
            assertThrows(IllegalArgumentException.class,
                    () -> FollowOwnerTool.parseArguments(args));
        }
    }

    @ParameterizedTest
    @EnumSource(FollowAction.class)
    void everyParsedActionIsPassedUnchangedToSharedControlInvoker(
            FollowAction action) {
        List<FollowAction> calls = new ArrayList<>();
        FollowControlResult expected = result(action, true);
        FollowOwnerTool tool = new FollowOwnerTool(
                CONFIG,
                (server, companion, actual, config) -> {
                    calls.add(actual);
                    assertSame(CONFIG, config);
                    return expected;
                });

        FollowControlResult actual =
                tool.invokeControl(null, null, action);

        assertSame(expected, actual);
        assertEquals(List.of(action), calls);
    }

    @Test
    void toolSerializesTheRealControlResultAndStableCode() {
        FollowControlResult expected =
                new FollowControlResult(
                        FollowAction.OFF,
                        true,
                        true,
                        "DISABLED",
                        "已关闭自动跟随并释放当前移动控制。",
                        status(false, false));

        JsonObject json = JsonParser.parseString(
                FollowOwnerTool.resultJson(expected)).getAsJsonObject();

        assertTrue(json.get("success").getAsBoolean());
        assertEquals(expected.message(), json.get("message").getAsString());
        assertEquals("DISABLED",
                json.getAsJsonObject("data").get("code").getAsString());
        assertTrue(json.getAsJsonObject("data").get("changed").getAsBoolean());
    }

    @Test
    void failedControlResultRemainsARealToolFailure() {
        FollowControlResult expected =
                new FollowControlResult(
                        FollowAction.PAUSE,
                        false,
                        false,
                        "PAUSE_REQUIRES_ENABLED",
                        "自动跟随当前已关闭；请使用 on 或 resume 启用。",
                        status(false, false));

        JsonObject json = JsonParser.parseString(
                FollowOwnerTool.resultJson(expected)).getAsJsonObject();

        assertFalse(json.get("success").getAsBoolean());
        assertEquals("PAUSE_REQUIRES_ENABLED",
                json.getAsJsonObject("data").get("code").getAsString());
    }

    @Test
    void actionEnumHasExactlyTheFiveApprovedNonPersistentValues() {
        assertEquals(List.of(
                        FollowAction.ON,
                        FollowAction.OFF,
                        FollowAction.PAUSE,
                        FollowAction.RESUME,
                        FollowAction.STATUS),
                List.of(FollowAction.values()));
        assertTrue(FollowAction.parse(" ReSuMe ").isPresent());
        assertTrue(FollowAction.parse("unknown").isEmpty());
    }

    private static FollowControlResult result(
            FollowAction action, boolean success) {
        return new FollowControlResult(
                action, success, true, action.name(), "result",
                status(true, false));
    }

    private static FollowStatus status(boolean enabled, boolean paused) {
        return new FollowStatus(
                UUID.randomUUID(),
                "Numen",
                enabled,
                paused,
                false,
                enabled
                        ? FollowRuntimeState.WAITING_FOR_OWNER
                        : FollowRuntimeState.DISABLED,
                enabled
                        ? FollowWaitingReason.OWNER_INVALID
                        : FollowWaitingReason.NONE,
                false, false, false, false, 0L,
                true, true, true, OptionalDouble.of(8.0),
                3.0, 5.5, 12.0, 24.0, 64.0, 100L);
    }
}
