package com.dwinovo.numen.client.command;

/** One slash-command completion candidate shown in the Numen chat input. */
public record CommandCandidate(String command, String description, String group,
                               boolean requiresArgument) {

    public CommandCandidate(String command, String description, String group) {
        this(command, description, group, false);
    }

    /** Text inserted into the field when this candidate is accepted. */
    public String completionText() {
        String base = command == null ? "" : command.stripTrailing();
        return requiresArgument ? base + " " : base;
    }
}
