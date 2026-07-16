package com.dwinovo.numen.task.reflex;

/**
 * Roster entry for a PURE-POLICY instinct — a consulted function with no tick,
 * no priority and no body time (constitution §1: "被咨询的纯函数策略"), e.g. the
 * tool durability guard in {@code ToolSelect} or the {@code FoodPolicy} filter.
 * The policy's code stays a static utility; this record only files its paperwork
 * ({@link ReflexRegistry}), and the policy consults
 * {@link ReflexRegistry#enabled} at its own decision-function entry.
 */
public record PolicyReflex(String id, String describe) implements Reflex {}
