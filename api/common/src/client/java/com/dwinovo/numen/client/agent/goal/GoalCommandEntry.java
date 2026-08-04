package com.dwinovo.numen.client.agent.goal;

/** One recorded goal command, used by {@code /goal recent}. */
public record GoalCommandEntry(String command, String result, long atMs) {

    public GoalCommandEntry {
        command = command == null ? "" : command;
        result = result == null ? "" : result;
        atMs = Math.max(0, atMs);
    }
}
