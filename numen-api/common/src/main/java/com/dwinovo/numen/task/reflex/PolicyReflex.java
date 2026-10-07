package com.dwinovo.numen.task.reflex;

/**
 * Roster entry for a PURE-POLICY instinct — a consulted function with no tick,
 * no priority and no body time (constitution §1: "被咨询的纯函数策略"), e.g. the
 * tool durability guard in {@code ToolSelect} or the {@code FoodPolicy} filter.
 * The policy's code stays a static utility; this record only files its paperwork
 * ({@link ReflexRegistry}) so the model sees it in the reflex overview.
 */
public record PolicyReflex(String id, String describe) implements Reflex {}
