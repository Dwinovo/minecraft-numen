package com.dwinovo.numen.core.follow;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Immutable, startup-scoped owner-follow configuration.
 *
 * <p>Unsafe world-changing capabilities are represented so an accidental
 * {@code true} in the user file can be diagnosed, but every effective instance
 * clamps those capabilities to {@code false}.
 */
public record FollowConfig(
        double stopDistance,
        double startDistance,
        double sprintDistance,
        double catchUpDistance,
        double lostDistance,
        long failedCooldownTicks,
        boolean allowBreak,
        boolean allowPlace,
        boolean allowWaterBucketLanding,
        boolean allowTeleport,
        boolean allowCrossDimension) {

    public static final double DEFAULT_STOP_DISTANCE = 3.0;
    public static final double DEFAULT_START_DISTANCE = 5.5;
    public static final double DEFAULT_SPRINT_DISTANCE = 12.0;
    public static final double DEFAULT_CATCH_UP_DISTANCE = 24.0;
    public static final double DEFAULT_LOST_DISTANCE = 64.0;
    public static final long DEFAULT_FAILED_COOLDOWN_TICKS = 100L;

    public static final String FILE_NAME = "auto_follow.json";

    private static final String STOP_DISTANCE = "stop_distance";
    private static final String START_DISTANCE = "start_distance";
    private static final String SPRINT_DISTANCE = "sprint_distance";
    private static final String CATCH_UP_DISTANCE = "catch_up_distance";
    private static final String LOST_DISTANCE = "lost_distance";
    private static final String FAILED_COOLDOWN_TICKS = "failed_cooldown_ticks";
    private static final String ALLOW_BREAK = "allow_break";
    private static final String ALLOW_PLACE = "allow_place";
    private static final String ALLOW_WATER_BUCKET_LANDING =
            "allow_water_bucket_landing";
    private static final String ALLOW_TELEPORT = "allow_teleport";
    private static final String ALLOW_CROSS_DIMENSION = "allow_cross_dimension";

    private static final Pattern INTEGER_TOKEN = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final BigInteger MAX_COOLDOWN =
            BigInteger.valueOf(Integer.MAX_VALUE);
    private static final Gson PRETTY =
            new GsonBuilder().setPrettyPrinting().create();

    public FollowConfig {
        if (!validNumericSet(stopDistance, startDistance, sprintDistance,
                catchUpDistance, lostDistance, failedCooldownTicks)) {
            stopDistance = DEFAULT_STOP_DISTANCE;
            startDistance = DEFAULT_START_DISTANCE;
            sprintDistance = DEFAULT_SPRINT_DISTANCE;
            catchUpDistance = DEFAULT_CATCH_UP_DISTANCE;
            lostDistance = DEFAULT_LOST_DISTANCE;
            failedCooldownTicks = DEFAULT_FAILED_COOLDOWN_TICKS;
        }
        allowBreak = false;
        allowPlace = false;
        allowWaterBucketLanding = false;
        allowTeleport = false;
        allowCrossDimension = false;
    }

    public static FollowConfig defaults() {
        return new FollowConfig(
                DEFAULT_STOP_DISTANCE,
                DEFAULT_START_DISTANCE,
                DEFAULT_SPRINT_DISTANCE,
                DEFAULT_CATCH_UP_DISTANCE,
                DEFAULT_LOST_DISTANCE,
                DEFAULT_FAILED_COOLDOWN_TICKS,
                false, false, false, false, false);
    }

    /**
     * The one immutable process-side configuration used by chains, tools, and
     * commands. There is intentionally no reload or mutation method.
     */
    public static FollowConfig current() {
        return CurrentHolder.INSTANCE;
    }

    public static Path defaultPath() {
        return Services.PLATFORM.getConfigDir()
                .resolve("numen")
                .resolve(FILE_NAME);
    }

    /**
     * Loads one path without changing startup-global state. Missing files are
     * seeded with canonical defaults; invalid existing files are left intact.
     */
    public static FollowConfig load(Path path) {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            FollowConfig defaults = defaults();
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, PRETTY.toJson(defaultJson()),
                        StandardCharsets.UTF_8);
            } catch (IOException | SecurityException exception) {
                Constants.LOG.warn(
                        "[owner-follow] could not create default configuration; "
                                + "using in-memory defaults: {}",
                        exception.getMessage());
            }
            return defaults;
        }

        try {
            JsonElement root = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("configuration root is not an object");
            }
            return parse(root.getAsJsonObject());
        } catch (IOException | RuntimeException exception) {
            Constants.LOG.warn(
                    "[owner-follow] invalid configuration left unchanged; "
                            + "using in-memory defaults: {}",
                    exception.getMessage());
            return defaults();
        }
    }

    private static FollowConfig parse(JsonObject root) {
        double stop = optionalDouble(root, STOP_DISTANCE, DEFAULT_STOP_DISTANCE);
        double start = optionalDouble(root, START_DISTANCE, DEFAULT_START_DISTANCE);
        double sprint = optionalDouble(root, SPRINT_DISTANCE, DEFAULT_SPRINT_DISTANCE);
        double catchUp = optionalDouble(
                root, CATCH_UP_DISTANCE, DEFAULT_CATCH_UP_DISTANCE);
        double lost = optionalDouble(root, LOST_DISTANCE, DEFAULT_LOST_DISTANCE);
        long cooldown = optionalCooldown(
                root, FAILED_COOLDOWN_TICKS, DEFAULT_FAILED_COOLDOWN_TICKS);

        boolean requestedUnsafeCapability = false;
        requestedUnsafeCapability |= unsafeRequested(root, ALLOW_BREAK);
        requestedUnsafeCapability |= unsafeRequested(root, ALLOW_PLACE);
        requestedUnsafeCapability |= unsafeRequested(
                root, ALLOW_WATER_BUCKET_LANDING);
        requestedUnsafeCapability |= unsafeRequested(root, ALLOW_TELEPORT);
        requestedUnsafeCapability |= unsafeRequested(root, ALLOW_CROSS_DIMENSION);
        if (requestedUnsafeCapability) {
            Constants.LOG.warn(
                    "[owner-follow] unsafe capability flags were requested but "
                            + "remain forcibly disabled");
        }

        if (!validNumericSet(stop, start, sprint, catchUp, lost, cooldown)) {
            throw new IllegalArgumentException(
                    "distance order must be stop < start < sprint <= catch_up < lost "
                            + "and cooldown must be a positive bounded integer");
        }
        return new FollowConfig(stop, start, sprint, catchUp, lost, cooldown,
                false, false, false, false, false);
    }

    private static double optionalDouble(
            JsonObject root, String name, double defaultValue) {
        JsonElement element = root.get(name);
        if (element == null) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(
                    name + " must be a finite positive number");
        }
        return value;
    }

    private static long optionalCooldown(
            JsonObject root, String name, long defaultValue) {
        JsonElement element = root.get(name);
        if (element == null) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        String token = element.getAsString();
        if (!INTEGER_TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        BigInteger value = new BigInteger(token);
        if (value.signum() <= 0 || value.compareTo(MAX_COOLDOWN) > 0) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and " + Integer.MAX_VALUE);
        }
        return value.longValueExact();
    }

    private static boolean unsafeRequested(JsonObject root, String name) {
        JsonElement element = root.get(name);
        if (element == null) {
            return false;
        }
        if (!element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static boolean validNumericSet(
            double stop,
            double start,
            double sprint,
            double catchUp,
            double lost,
            long cooldown) {
        return Double.isFinite(stop) && stop > 0.0
                && Double.isFinite(start) && start > 0.0
                && Double.isFinite(sprint) && sprint > 0.0
                && Double.isFinite(catchUp) && catchUp > 0.0
                && Double.isFinite(lost) && lost > 0.0
                && stop < start
                && start < sprint
                && sprint <= catchUp
                && catchUp < lost
                && cooldown > 0L
                && cooldown <= Integer.MAX_VALUE;
    }

    private static JsonObject defaultJson() {
        JsonObject root = new JsonObject();
        root.addProperty(STOP_DISTANCE, DEFAULT_STOP_DISTANCE);
        root.addProperty(START_DISTANCE, DEFAULT_START_DISTANCE);
        root.addProperty(SPRINT_DISTANCE, DEFAULT_SPRINT_DISTANCE);
        root.addProperty(CATCH_UP_DISTANCE, DEFAULT_CATCH_UP_DISTANCE);
        root.addProperty(LOST_DISTANCE, DEFAULT_LOST_DISTANCE);
        root.addProperty(FAILED_COOLDOWN_TICKS, DEFAULT_FAILED_COOLDOWN_TICKS);
        root.addProperty(ALLOW_BREAK, false);
        root.addProperty(ALLOW_PLACE, false);
        root.addProperty(ALLOW_WATER_BUCKET_LANDING, false);
        root.addProperty(ALLOW_TELEPORT, false);
        root.addProperty(ALLOW_CROSS_DIMENSION, false);
        return root;
    }

    private static final class CurrentHolder {
        private static final FollowConfig INSTANCE = load(defaultPath());

        private CurrentHolder() {}
    }
}
