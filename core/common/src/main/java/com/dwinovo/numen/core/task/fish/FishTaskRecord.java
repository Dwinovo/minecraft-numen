package com.dwinovo.numen.core.task.fish;

import com.dwinovo.numen.task.TaskRecord;

/** Typed descriptor and live progress for the {@code fish} background task. */
public final class FishTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "fish";

    /** 要钓几条;<b>0 = 一直钓</b>(常驻,直到主人换掉这件活)。 */
    public final int requested;

    private int caught;
    private int casts;

    public FishTaskRecord(String toolCallId, long deadlineGameTime, int requested) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.requested = requested;
    }

    public int caught() {
        return caught;
    }

    public int casts() {
        return casts;
    }

    public void caughtOne() {
        caught++;
    }

    public void castOnce() {
        casts++;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + caught + "/" + requested;
    }
}
