package com.dwinovo.numen.client.command;

import java.util.List;

/**
 * Pure decision rules for Enter/Tab completion, kept outside the Minecraft UI
 * so the exact "complete vs send" and "cycle vs fill" cases are unit-testable.
 */
public final class CommandCompletionPolicy {

    private CommandCompletionPolicy() {}

    public record EnterDecision(boolean send, String text) {}

    public record TabDecision(String text, int selectedIndex) {}

    public static EnterDecision enter(String current, List<CommandCandidate> candidates,
                                      int selectedIndex) {
        String text = current == null ? "" : current.trim();
        if (candidates.isEmpty()) return new EnterDecision(true, text);
        CommandCandidate exact = exactCandidate(candidates, text);
        if (exact != null) {
            return exact.requiresArgument()
                    ? new EnterDecision(false, exact.completionText())
                    : new EnterDecision(true, text);
        }
        int index = clamp(selectedIndex, candidates.size());
        return new EnterDecision(false, candidates.get(index).completionText());
    }

    public static TabDecision tab(String current, List<CommandCandidate> candidates,
                                  int selectedIndex, int direction) {
        if (candidates.isEmpty()) return new TabDecision(current == null ? "" : current, 0);
        String text = current == null ? "" : current.trim();
        int index = indexOfExact(candidates, text);
        if (index >= 0 && candidates.get(index).requiresArgument()) {
            return new TabDecision(candidates.get(index).completionText(), index);
        }
        if (index < 0) {
            int selected = clamp(selectedIndex, candidates.size());
            return new TabDecision(candidates.get(selected).completionText(), selected);
        }
        int step = direction == 0 ? 1 : Integer.signum(direction);
        int next = Math.floorMod(index + step, candidates.size());
        return new TabDecision(candidates.get(next).command(), next);
    }

    private static CommandCandidate exactCandidate(List<CommandCandidate> candidates, String text) {
        for (CommandCandidate candidate : candidates) {
            if (candidate.command().equalsIgnoreCase(text)) return candidate;
        }
        return null;
    }

    private static int indexOfExact(List<CommandCandidate> candidates, String text) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).command().equalsIgnoreCase(text)) return i;
        }
        return -1;
    }

    private static int clamp(int index, int size) {
        return Math.max(0, Math.min(index, Math.max(0, size - 1)));
    }
}
