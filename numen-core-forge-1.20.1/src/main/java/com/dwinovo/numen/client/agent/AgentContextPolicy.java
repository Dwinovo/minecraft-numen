package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.llm.ConvoState;

import java.util.List;
import java.time.Duration;

/** Pure context-budget and recent-directive policy for an entity agent loop. */
public final class AgentContextPolicy {
    public static final int AUTO_COMPACT_BUFFER_TOKENS = 13_000;
    static final int MIN_COMPACT_MESSAGES = 8;
    static final int MAX_COMPACT_FAILURE_LEVEL = 3;
    static final Duration COMPACTION_TIMEOUT = Duration.ofSeconds(45);
    private static final long[] FAILURE_COOLDOWNS_MS = {120_000L, 300_000L, 900_000L};

    private AgentContextPolicy() { }

    public static int compactThreshold(int contextWindow, int configuredLimit) {
        int safeWindowLimit = Math.max(1, contextWindow - AUTO_COMPACT_BUFFER_TOKENS);
        return Math.min(safeWindowLimit,
                CompanionAiConfigStore.normalizeAutoCompactTokens(configuredLimit));
    }

    static boolean shouldCompact(int contextWindow, int configuredLimit, int contextTokens, int messageCount,
                                 boolean hasUnreadToolResult,
                                 long nowMs, long cooldownUntilMs) {
        return !hasUnreadToolResult
                && nowMs >= cooldownUntilMs
                && contextTokens >= compactThreshold(contextWindow, configuredLimit)
                && messageCount >= MIN_COMPACT_MESSAGES;
    }

    static long failureCooldownMillis(int failures) {
        if (failures <= 0) return 0L;
        int index = Math.max(0, Math.min(failures, FAILURE_COOLDOWNS_MS.length) - 1);
        return FAILURE_COOLDOWNS_MS[index];
    }

    static String recentUserDirectives(List<ConvoState.Msg> messages, int limit) {
        StringBuilder out = new StringBuilder();
        int found = 0;
        for (int i = messages.size() - 1; i >= 0 && found < limit; i--) {
            if (messages.get(i) instanceof ConvoState.Msg.User user) {
                if (!out.isEmpty()) out.append(' ');
                out.append(user.content());
                found++;
            }
        }
        return out.toString();
    }
}
