package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class FollowConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsAreExactAndAllWorldChangingCapabilitiesAreOff() {
        assertDefaults(FollowConfig.defaults());
    }

    @Test
    void missingFileUsesDefaultsAndWritesCanonicalFile() throws IOException {
        Path file = tempDir.resolve("numen").resolve("auto_follow.json");

        FollowConfig loaded = FollowConfig.load(file);

        assertDefaults(loaded);
        assertTrue(Files.isRegularFile(file));
        JsonObject json = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(Set.of(
                "stop_distance",
                "start_distance",
                "sprint_distance",
                "catch_up_distance",
                "lost_distance",
                "failed_cooldown_ticks",
                "allow_break",
                "allow_place",
                "allow_water_bucket_landing",
                "allow_teleport",
                "allow_cross_dimension"), json.keySet());
    }

    @Test
    void creationFailureDoesNotPreventInMemoryDefaults() throws IOException {
        Path parentFile = tempDir.resolve("not-a-directory");
        Files.writeString(parentFile, "occupied", StandardCharsets.UTF_8);

        FollowConfig loaded =
                FollowConfig.load(parentFile.resolve("auto_follow.json"));

        assertDefaults(loaded);
    }

    @Test
    void validCompleteConfigurationLoads() throws IOException {
        Path file = write(validJson(2.0, 4.0, 9.0, 20.0, 50.0, "240"));

        FollowConfig config = FollowConfig.load(file);

        assertEquals(2.0, config.stopDistance());
        assertEquals(4.0, config.startDistance());
        assertEquals(9.0, config.sprintDistance());
        assertEquals(20.0, config.catchUpDistance());
        assertEquals(50.0, config.lostDistance());
        assertEquals(240L, config.failedCooldownTicks());
    }

    @Test
    void missingNumericFieldsUseDefaultsBeforeValidation() throws IOException {
        Path file = write("{\"stop_distance\":2.0,\"start_distance\":4.0}");

        FollowConfig config = FollowConfig.load(file);

        assertEquals(2.0, config.stopDistance());
        assertEquals(4.0, config.startDistance());
        assertEquals(12.0, config.sprintDistance());
        assertEquals(100L, config.failedCooldownTicks());
    }

    @Test
    void unknownFieldsAreIgnored() throws IOException {
        String json = validJson(2.0, 4.0, 9.0, 20.0, 50.0, "240")
                .replaceFirst("\\}$", ",\"future_field\":{\"x\":1}}");

        FollowConfig config = FollowConfig.load(write(json));

        assertEquals(2.0, config.stopDistance());
        assertEquals(240L, config.failedCooldownTicks());
    }

    @Test
    void damagedJsonFallsBackToAllNumericDefaults() throws IOException {
        assertDefaults(FollowConfig.load(write("{not-json")));
    }

    @Test
    void nonObjectRootFallsBackToAllNumericDefaults() throws IOException {
        assertDefaults(FollowConfig.load(write("[1,2,3]")));
    }

    @Test
    void knownFieldTypeErrorFallsBackToAllNumericDefaults() throws IOException {
        JsonObject root = JsonParser.parseString(
                validJson(2.0, 4.0, 9.0, 20.0, 50.0, "240")).getAsJsonObject();
        root.addProperty("stop_distance", "two");

        assertDefaults(FollowConfig.load(write(root.toString())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "0", "-1"})
    void nonFiniteOrNonPositiveDistanceFallsBackToAllDefaults(String token)
            throws IOException {
        assertDefaults(FollowConfig.load(write(
                validJsonRaw(token, "4.0", "9.0", "20.0", "50.0", "240"))));
    }

    @Test
    void stopAtOrAboveStartFallsBackToDefaults() throws IOException {
        assertDefaults(FollowConfig.load(write(
                validJson(4.0, 4.0, 9.0, 20.0, 50.0, "240"))));
    }

    @Test
    void startAtOrAboveSprintFallsBackToDefaults() throws IOException {
        assertDefaults(FollowConfig.load(write(
                validJson(2.0, 9.0, 9.0, 20.0, 50.0, "240"))));
    }

    @Test
    void sprintAboveCatchUpFallsBackToDefaults() throws IOException {
        assertDefaults(FollowConfig.load(write(
                validJson(2.0, 4.0, 21.0, 20.0, 50.0, "240"))));
    }

    @Test
    void catchUpAtLostFallsBackToDefaults() throws IOException {
        assertDefaults(FollowConfig.load(write(
                validJson(2.0, 4.0, 9.0, 50.0, 50.0, "240"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "0", "-1", "2147483648"})
    void invalidCooldownFallsBackToAllDefaults(String token) throws IOException {
        assertDefaults(FollowConfig.load(write(
                validJson(2.0, 4.0, 9.0, 20.0, 50.0, token))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "allow_break",
            "allow_place",
            "allow_water_bucket_landing",
            "allow_teleport",
            "allow_cross_dimension"
    })
    void requestedUnsafeCapabilityStaysFalseWithoutDiscardingValidNumbers(
            String field) throws IOException {
        String json = validJson(2.0, 4.0, 9.0, 20.0, 50.0, "240")
                .replaceFirst("\\}$", ",\"" + field + "\":true}");

        FollowConfig config = FollowConfig.load(write(json));

        assertEquals(2.0, config.stopDistance());
        assertEquals(240L, config.failedCooldownTicks());
        assertAllCapabilitiesFalse(config);
    }

    @Test
    void invalidExistingFileIsNeverOverwritten() throws IOException {
        Path file = write("{broken-user-content");
        String before = Files.readString(file, StandardCharsets.UTF_8);

        FollowConfig.load(file);

        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void separateLoadCallsDoNotCreateMutableGlobalConfiguration() throws IOException {
        Path first = tempDir.resolve("first.json");
        Path second = tempDir.resolve("second.json");
        Files.writeString(first,
                validJson(2.0, 4.0, 9.0, 20.0, 50.0, "200"),
                StandardCharsets.UTF_8);
        Files.writeString(second,
                validJson(2.5, 4.5, 10.0, 21.0, 51.0, "201"),
                StandardCharsets.UTF_8);

        FollowConfig firstConfig = FollowConfig.load(first);
        FollowConfig secondConfig = FollowConfig.load(second);

        assertEquals(2.0, firstConfig.stopDistance());
        assertEquals(2.5, secondConfig.stopDistance());
        assertNotEquals(firstConfig, secondConfig);
    }

    @Test
    void publicConstructionAlsoClampsUnsafeFlagsAndInvalidNumbers() {
        FollowConfig config = new FollowConfig(
                -1.0, 1.0, 1.0, 1.0, 1.0, -1L,
                true, true, true, true, true);

        assertDefaults(config);
    }

    private Path write(String json) throws IOException {
        Path file = tempDir.resolve("auto_follow-" + System.nanoTime() + ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static String validJson(
            double stop,
            double start,
            double sprint,
            double catchUp,
            double lost,
            String cooldown) {
        return validJsonRaw(
                Double.toString(stop),
                Double.toString(start),
                Double.toString(sprint),
                Double.toString(catchUp),
                Double.toString(lost),
                cooldown);
    }

    private static String validJsonRaw(
            String stop,
            String start,
            String sprint,
            String catchUp,
            String lost,
            String cooldown) {
        return """
                {
                  "stop_distance": %s,
                  "start_distance": %s,
                  "sprint_distance": %s,
                  "catch_up_distance": %s,
                  "lost_distance": %s,
                  "failed_cooldown_ticks": %s,
                  "allow_break": false,
                  "allow_place": false,
                  "allow_water_bucket_landing": false,
                  "allow_teleport": false,
                  "allow_cross_dimension": false
                }
                """.formatted(stop, start, sprint, catchUp, lost, cooldown);
    }

    private static void assertDefaults(FollowConfig config) {
        assertEquals(3.0, config.stopDistance());
        assertEquals(5.5, config.startDistance());
        assertEquals(12.0, config.sprintDistance());
        assertEquals(24.0, config.catchUpDistance());
        assertEquals(64.0, config.lostDistance());
        assertEquals(100L, config.failedCooldownTicks());
        assertAllCapabilitiesFalse(config);
    }

    private static void assertAllCapabilitiesFalse(FollowConfig config) {
        assertFalse(config.allowBreak());
        assertFalse(config.allowPlace());
        assertFalse(config.allowWaterBucketLanding());
        assertFalse(config.allowTeleport());
        assertFalse(config.allowCrossDimension());
    }
}
