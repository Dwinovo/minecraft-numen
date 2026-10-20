package com.dwinovo.numen.tools.work;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.nav.Walked;
import com.dwinovo.numen.task.move.Arrival;
import com.dwinovo.numen.task.move.FollowTaskRecord;
import com.dwinovo.numen.task.move.MoveToTaskRecord;
import com.dwinovo.numen.task.move.Where;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.EntityRef;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Job;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * {@code numen.move}:移动——去一处({@code to})、离开某物({@code away})、跟着谁走一阵({@code follow})。三个都是同一套走法
 * ({@link com.dwinovo.numen.nav.Walking}):默认可挖可搭,只看价钱;能不能动哪一格由权限层在动手那一刻决定,不在这里判。模型只说去哪、
 * 多近算到,怎么走归寻路。
 */
public final class MoveApi {

    /** follow 默认跟到几米内。3 米大致是"就在旁边"又不至于挤到主人身上。 */
    private static final int DEFAULT_DISTANCE = 3;
    private static final int MIN_DISTANCE = 2;
    private static final int MAX_DISTANCE = 16;
    /** follow 默认跟多久:一分钟,够走一段路,又不至于让程序一直等着。 */
    private static final int DEFAULT_FOLLOW_SECONDS = 60;
    /** follow 至多跟多久:一个游戏日。 */
    private static final int MAX_FOLLOW_SECONDS = 20 * 60;

    private MoveApi() {}

    public static void install(NumenApi numen) {
        numen.codec(Where.class, Where.CODEC);
        numen.api("move", "Moving: go to a place, get away from something, follow someone for a while. They all walk "
                + "the same way: the cheapest, digging and building only when going around costs more.", MoveApi.class);
    }

    /** 去哪儿、多近算到。 */
    public record To(@Doc("Where: a Pos, a Block, an Entity (where it is now), a Cluster or Cells from a scan (any of its "
            + "cells), a column {x = …, z = …} or a height {y = …}.") Where where,
                     @Doc("How close counts as there, in blocks (1-" + Arrival.MAX_RANGE + ").")
                     @Omitted("stand in it; a Block, an Entity or a Cluster you can't stand in, go beside (within "
                             + Arrival.BESIDE + " blocks)") @Positional Optional<Integer> distance) {}

    @Fn("Walk to a place, digging and building on the way when going around costs more.")
    @Example("numen.move.to({x = 120, y = 64, z = -35})")
    @Example("numen.move.to(numen.scan.blocks(\"iron_ore\")[1].nearest)")
    @Example("numen.move.to(creeper, 6)")
    @Example("numen.move.to({x = 120, z = -35})")
    @Example("numen.move.to({y = 12})")
    @Note("Background work with an end: it returns when you are there or can't get further, and says how it went: where you "
            + "stand, how far you still were from where you were heading, how many blocks it broke and placed, what your "
            + "owner and the rules ruled about the cells it wanted to change. A walk that stops short fails with its "
            + "reason and the same account in the error's data.")
    @Note("Without a distance a Pos, a column or a height is stood in (a Pos inside rock is dug into); a Block, an Entity "
            + "or a Cluster is gone beside. With a distance, within that many blocks of it.")
    @Note("It walks where it can and digs, bridges or pillars only when going around costs more. Which blocks it may "
            + "change is your owner's rules to say: cells they forbid are walls to it, cells they ask about cost extra and "
            + "your owner is asked when the walk gets to one. A cell your owner refuses is a wall for the rest of this "
            + "walk: it plans around it, and fails with kind denied when there is no way around. It never digs the block "
            + "under its feet or gravel overhead, never lets liquid in, and takes a drop only as high as your health "
            + "allows.")
    @Note("Sitting in a boat, it sails to the water nearest the place; a place on land ends at the shore, still sitting "
            + "in the boat. Any other vehicle, or a place that is not a Pos or a column, fails at once and names "
            + "`numen.use.dismount()`: a walk never steps off by itself.")
    @Note("A Pos, Block or Cluster that can't be stood in, used or reached as asked, or an Entity that is gone, fails "
            + "at once with the reason and nothing starts.")
    @SeeAlso({"numen.move.away", "numen.move.follow", "numen.use.dismount", "numen.task.stop"})
    public static Job<Walked> to(ServerCall call, To args) {
        return Job.of(new MoveToTaskRecord(call, Arrival.to(args.where(), args.distance().orElse(null))));
    }

    /** 离开什么、多远。 */
    public record Away(@Doc("What to get away from: a Pos, a Block, an Entity (where it is now), a Cluster or Cells.")
                       Where from,
                       @Doc("How far to get, in blocks (1-" + Arrival.MAX_RANGE + ").") int distance) {}

    @Fn("Walk until you are at least a distance away from something.")
    @Example("numen.move.away(creeper, 12)")
    @Example("numen.move.away({x = 120, y = 64, z = -35}, 8)")
    @Note("The same walk as `numen.move.to`, with the same account when it ends: it is done once you are that far from "
            + "every cell of it, and ends there.")
    @Note("Sitting in a boat or on a mount, it fails at once and names `numen.use.dismount()`.")
    @SeeAlso({"numen.move.to", "numen.task.stop"})
    public static Job<Walked> away(ServerCall call, Away args) {
        return Job.of(new MoveToTaskRecord(call, Arrival.away(args.from(), args.distance())));
    }

    /** 跟谁、多近、多久。 */
    public record Follow(@Doc("Who to follow: an Entity from numen.scan.entities, or its id (zombie@3f9a1c).")
                         @Omitted("follow your owner") @Positional Optional<EntityRef> entity,
                         @Doc("How close to stay, in blocks (" + MIN_DISTANCE + "-" + MAX_DISTANCE + ").")
                         @Omitted("stay within " + DEFAULT_DISTANCE) Optional<Integer> distance,
                         @Doc("Follow for this many seconds of world time (up to " + MAX_FOLLOW_SECONDS + "), then "
                                 + "stop and return.") @Omitted("follow for " + DEFAULT_FOLLOW_SECONDS + " s")
                         Optional<Integer> seconds) {}

    /**
     * 跟着走:不点名就是跟主人,点名了就跟那一只——两者目标消失时的含义不同,见 {@code FollowTaskRecord#target}。点名的那只按 UUID 认:
     * 记录里存它,受理这一刻先认到它,认不到当场失败。总有期限:不给秒数就跟一分钟。
     */
    @Fn("Tag along with your owner, or with an entity you name, for a while.")
    @Example("numen.move.follow()")
    @Example("numen.move.follow(\"player@ab12cd\", {distance = 5, seconds = 30})")
    @Note("Background work with an end: it returns after seconds (default " + DEFAULT_FOLLOW_SECONDS + "), or when "
            + "your owner stops it, with the same account of the walk as `numen.move.to`. You go quiet while already "
            + "beside them.")
    @Note("Following a named entity ends if it dies or leaves the loaded area; following your owner just waits while "
            + "they are offline. When they are already out of reach as you call it, the call is refused with the "
            + "reason and nothing starts.")
    @Note("It walks the way `numen.move.to` does: it digs, bridges or pillars to keep up when going around costs more, "
            + "within what your owner's rules allow, and says so in the account.")
    @SeeAlso({"numen.scan.entities", "numen.move.to", "numen.task.stop"})
    public static Job<Walked> follow(ServerCall call, Follow args) {
        int distance = Math.clamp(args.distance().orElse(DEFAULT_DISTANCE), MIN_DISTANCE, MAX_DISTANCE);
        long ticks = Math.clamp(args.seconds().orElse(DEFAULT_FOLLOW_SECONDS), 1, MAX_FOLLOW_SECONDS) * 20L;
        if (args.entity().isEmpty()) {
            return Job.of(new FollowTaskRecord(call, distance, null, null, ticks));
        }
        Entity target = call.entity(args.entity().get());
        return Job.of(new FollowTaskRecord(call, distance, target.getUUID(), target.getName().getString(), ticks));
    }
}
