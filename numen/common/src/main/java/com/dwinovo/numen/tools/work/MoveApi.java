package com.dwinovo.numen.tools.work;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.nav.Walked;
import com.dwinovo.numen.pathing.api.Travel;
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
 * {@code numen.move}:移动——走到一处({@code walk})、飞到一处({@code fly})、离开某物({@code away})、跟着谁走一阵({@code follow})。
 * 走只走(默认可挖可搭,只看价钱),从不飞,{@code away}、{@code follow} 同样;飞只飞(许飞的创造飞行,否则穿着鞘翅滑翔)。走还是飞由调用方说,
 * 寻路不比价钱;能不能动哪一格由权限层在动手那一刻决定,不在这里判。模型只说去哪、多近算到,怎么走(怎么飞)归寻路。
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
        numen.api("move", "Moving, one verb for each way of going. `walk` goes to a place on foot: walking and swimming, digging and "
                + "building only when going around costs more. `fly` goes by air: creative flight when you may fly, otherwise "
                + "gliding on the elytra you wear. `away` and `follow` go on foot. A trip ends with the account of how it went.",
                MoveApi.class);
    }

    /** 去哪儿、多近算到:走与飞同一份。 */
    public record Walk(@Doc("Where: a Pos, a Block, an Entity (where it is now), a Cluster or Cells from a scan (any of its "
            + "cells), a column {x = …, z = …} or a height {y = …}.") Where where,
                       @Doc("How close counts as there, in blocks (1-" + Arrival.MAX_RANGE + ").")
                       @Omitted("stand in it; a Block, an Entity or a Cluster you can't stand in, go beside (within "
                               + Arrival.BESIDE + " blocks)") @Positional Optional<Integer> distance) {}

    @Fn("Go to a place on foot: walk and swim, dig and build when going around costs more. It never flies.")
    @Example("numen.move.walk({x = 120, y = 64, z = -35})")
    @Example("numen.move.walk(numen.scan.blocks(\"iron_ore\")[1].nearest)")
    @Example("numen.move.walk(creeper, 6)")
    @Example("numen.move.walk({x = 120, z = -35})")
    @Example("numen.move.walk({y = 12})")
    @Note("Use it for ordinary trips over ground you can reach; `numen.move.fly` crosses what can't be walked and long open distances.")
    @Note("Background work with an end: it returns when you are there or can't get further, and says how it went: where you "
            + "stand, how far you still were from where you were heading, how many blocks it broke and placed, what your "
            + "owner and the rules ruled about the cells it wanted to change. A trip that stops short fails with its "
            + "reason and the same account in the error's data.")
    @Note("Without a distance a Pos, a column or a height is stood in (a Pos inside rock is dug into); a Block, an Entity "
            + "or a Cluster is gone beside. With a distance, within that many blocks of it.")
    @Note("It walks where it can and digs, bridges or pillars only when going around costs more. Which blocks it may "
            + "change is your owner's rules to say: cells they forbid are walls to it, cells they ask about cost extra and "
            + "your owner is asked when the trip gets to one. A cell your owner refuses is a wall for the rest of this "
            + "trip: it plans around it, and fails with kind denied when there is no way around. It never digs the block "
            + "under its feet or gravel overhead, never lets liquid in, and takes a drop only as high as your health "
            + "allows.")
    @Note("With creative flight on it still walks: if you are flying when it starts it first comes down to the ground "
            + "(the account says so) and goes on from there.")
    @Note("Sitting in a boat, it sails to the water nearest the place; a place on land ends at the shore, still sitting "
            + "in the boat. Any other vehicle, or a place that is not a Pos or a column, fails at once and names "
            + "`numen.use.dismount()`: a trip never steps off by itself.")
    @Note("A Pos, Block or Cluster that can't be stood in, used or reached as asked, or an Entity that is gone, fails "
            + "at once with the reason and nothing starts.")
    @SeeAlso({"numen.move.fly", "numen.move.away", "numen.move.follow", "numen.use.dismount", "numen.task.stop"})
    public static Job<Walked> walk(ServerCall call, Walk args) {
        return Job.of(new MoveToTaskRecord(call, Arrival.to(args.where(), args.distance().orElse(null)), Travel.WALK));
    }

    @Fn("Go to a place by air: creative flight when you may fly, otherwise gliding on the elytra you wear.")
    @Example("numen.move.fly({x = 120, y = 64, z = -35})")
    @Example("numen.move.fly({x = 400, z = -200})")
    @Example("numen.move.fly(numen.scan.blocks(\"iron_ore\")[1].nearest, 4)")
    @Note("The same place and distance as `numen.move.walk`. Use it to cross gaps, water and mountains, and long open "
            + "distances; a place close by is `numen.move.walk`.")
    @Note("Background work with an end, with the same account as `numen.move.walk`, plus how far you flew or glided, where "
            + "you took off and came down, the rockets used and the elytra's wear. A trip that stops short fails with its reason "
            + "and the same account in the error's data.")
    @Note("If you may fly (creative), it flies there and lands, or hovers when the place is a Pos in mid-air.")
    @Note("Otherwise it glides: it walks to a spot nearby with open sky, takes off, climbs on firework rockets from your pack "
            + "or offhand to a height above the terrain on the way, glides to the place and lands about 6 blocks short of "
            + "it, and walks the rest. Gliding needs a place with x and z (a height alone has none).")
    @Note("Gliding needs an unbroken elytra worn (`numen.gear.wear`), at least 5 firework rockets in your pack or offhand (with "
            + "fewer it can still step off a ledge nearby and glide down, using none) and at least 5 durability left on "
            + "the elytra. When one of these is missing, or there is no open spot to take off, no way past the terrain, or no "
            + "dry ground to land on near the place, the call is refused at once and says what is missing and what to do.")
    @Note("In the air it comes down early where the ground is near when the rockets fall below 5 or the elytra's durability "
            + "below 5, or something blocks the way. If it lands far from the place and it can fly again it takes off again "
            + "from there; if it can't, the call ends there and fails with the reason and the account. It does not walk the "
            + "rest of a trip it can't fly: `numen.move.walk` or `numen.move.fly` again goes on from where you stand.")
    @Note("Sitting in a boat or on a mount, it fails at once and names `numen.use.dismount()`.")
    @SeeAlso({"numen.move.walk", "numen.gear.wear", "numen.use.glide", "numen.use.dismount", "numen.task.stop"})
    public static Job<Walked> fly(ServerCall call, Walk args) {
        return Job.of(new MoveToTaskRecord(call, Arrival.to(args.where(), args.distance().orElse(null)), Travel.FLY));
    }

    /** 离开什么、多远。 */
    public record Away(@Doc("What to get away from: a Pos, a Block, an Entity (where it is now), a Cluster or Cells.")
                       Where from,
                       @Doc("How far to get, in blocks (1-" + Arrival.MAX_RANGE + ").") int distance) {}

    @Fn("Get at least a distance away from something, on foot.")
    @Example("numen.move.away(creeper, 12)")
    @Example("numen.move.away({x = 120, y = 64, z = -35}, 8)")
    @Note("The same trip as `numen.move.walk`, on foot, with the same account when it ends: it is done once you are that far from "
            + "every cell of it, and ends there.")
    @Note("Sitting in a boat or on a mount, it fails at once and names `numen.use.dismount()`.")
    @SeeAlso({"numen.move.walk", "numen.task.stop"})
    public static Job<Walked> away(ServerCall call, Away args) {
        return Job.of(new MoveToTaskRecord(call, Arrival.away(args.from(), args.distance()), Travel.WALK));
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
    @Fn("Tag along with your owner, or with an entity you name, on foot for a while.")
    @Example("numen.move.follow()")
    @Example("numen.move.follow(\"player@ab12cd\", {distance = 5, seconds = 30})")
    @Note("Background work with an end: it returns after seconds (default " + DEFAULT_FOLLOW_SECONDS + "), or when "
            + "your owner stops it, with the same account of the trip as `numen.move.walk`. You go quiet while already "
            + "beside them.")
    @Note("Following a named entity ends if it dies or leaves the loaded area; following your owner just waits while "
            + "they are offline. When they are already out of reach as you call it, the call is refused with the "
            + "reason and nothing starts.")
    @Note("It travels the way `numen.move.walk` does, on foot: it digs, bridges or pillars to keep up when going around costs "
            + "more, within what your owner's rules allow, and says so in the account.")
    @SeeAlso({"numen.scan.entities", "numen.move.walk", "numen.task.stop"})
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
