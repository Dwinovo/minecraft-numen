package com.dwinovo.numen.pathing.drive;

/**
 * 一次飞行受理的结论:飞得起来(带着排好的滑翔计划;许飞的身体走创造飞行,不需要计划),或者缺什么({@link Lack})。
 * 受理时问一次、当场拒绝;计划随结论交给开出去的行程({@link Voyage}),不在第一刻再排一遍。
 */
public final class Admission {

    private static final Admission CREATIVE = new Admission(null, null);

    private final Glide.Plan plan;
    private final Lack lack;

    private Admission(Glide.Plan plan, Lack lack) {
        this.plan = plan;
        this.lack = lack;
    }

    /** 许飞的身体:创造飞行进寻路本身,没有滑翔计划。 */
    static Admission creative() {
        return CREATIVE;
    }

    static Admission glide(Glide.Plan plan) {
        return new Admission(plan, null);
    }

    static Admission lacking(Lack lack) {
        return new Admission(null, lack);
    }

    /** 缺什么;受理了为 null。 */
    public Lack lack() {
        return lack;
    }

    /** 排好的滑翔计划;创造飞行或没受理为 null。 */
    Glide.Plan plan() {
        return plan;
    }
}
