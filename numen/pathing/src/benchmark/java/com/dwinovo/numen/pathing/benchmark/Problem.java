package com.dwinovo.numen.pathing.benchmark;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

/**
 * 一道题:在哪块地、从哪到哪、什么目标。题库文件里一行一道题,只由地形与固定随机种子定出,不依赖任何搜索算法;
 * 两种规格各自的标准答案是另一个文件({@link Answers})。
 *
 * @param id       地块名-档-序号,题的唯一名字;dev 与封存的划分按它的哈希({@link Split})
 * @param bucket   起终点水平直线距离的档({@link Bucket})
 * @param distance 起终点水平直线距离
 * @param goalType 目标种类
 * @param radius   {@code NEAR} 的半径;其余为 0
 * @param endClass 终点是哪一类格子
 * @param split    调参用还是封存
 * @param tags     几何标签({@link Tag}),报表分组用
 */
record Problem(String id, String region, Bucket bucket, double distance, BlockPos from, GoalType goalType, BlockPos to, int radius,
               EndClass endClass, Split split, List<Tag> tags) {

    /** 起终点水平直线距离的档:与价钱模型无关,题目永远稳定。 */
    enum Bucket {
        SHORT("short", 0, 32), MEDIUM("medium", 32, 128), LONG("long", 128, 400);

        final String label;
        final double min;
        final double max;

        Bucket(String label, double min, double max) {
            this.label = label;
            this.min = min;
            this.max = max;
        }

        static Bucket of(double distance) {
            for (Bucket b : values()) {
                if (distance > b.min && distance <= b.max) {
                    return b;
                }
            }
            return null;
        }

        static Bucket named(String label) {
            for (Bucket b : values()) {
                if (b.label.equals(label)) {
                    return b;
                }
            }
            throw new IllegalArgumentException("没有这一档:" + label);
        }
    }

    /** 模型实际会下的三种令:走到某格、走到某格附近(跟随)、挖到某格。 */
    enum GoalType { AT, NEAR, DIG }

    /** 终点格的种类:地表(含水面)、洞穴地面、封闭的地下空腔、封闭在实心里的一格。 */
    enum EndClass {
        SURFACE, CAVE, CAVITY, BURIED;

        /** 终点在地下:要下洞。 */
        boolean underground() {
            return this != SURFACE;
        }

        /** 终点被封死(要挖开才进得去)。 */
        boolean sealed() {
            return this == CAVITY || this == BURIED;
        }
    }

    /** 调参(dev)还是封存(held)。 */
    enum Split { DEV, HELD }

    /**
     * 报表分组用的几何标签,只看地形与题,不看搜索的结果:
     * 起终点之间的直线上有水({@code water});直线不是平稳的一路平走——有高于一格的台阶或没有地面的列({@code rough});
     * 终点被封死、要挖才进得去({@code dig});终点在地下({@code underground})。
     */
    enum Tag {
        WATER("water"), ROUGH("rough"), DIG("dig"), UNDERGROUND("underground");

        final String label;

        Tag(String label) {
            this.label = label;
        }

        static Tag named(String label) {
            for (Tag t : values()) {
                if (t.label.equals(label)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("没有这个标签:" + label);
        }
    }

    /**
     * 这种规格下按构造到不了:规格 A 不挖不垫,"走到"一格封死的空腔或封闭在实心里的一格,进不去。题库里单独成一类,量多快认输;
     * 不跑标准答案求解器(搜索看不到区域外的世界,在开阔的地形上证明不了"搜完了")。
     */
    boolean unsolvable(Spec spec) {
        return spec == Spec.A && goalType == GoalType.AT && endClass.sealed();
    }

    /** 这道题的目标。 */
    Goal goal(BodyStats body) {
        return goal(goalType, to, radius, body);
    }

    /** 一种目标种类、终点格与半径对应的搜索目标:出题与评测都按它建,题的目标只在这里定义。 */
    static Goal goal(GoalType type, BlockPos to, int radius, BodyStats body) {
        return switch (type) {
            case AT -> Goals.at(to);
            case NEAR -> Goals.within(Goals.at(to), 0, radius);
            case DIG -> Goals.dig(to, body, Goals.Clearing.ANY);
        };
    }

    /** 题的 id 按哈希五五开:同一道题永远在同一边。 */
    static Split split(String id) {
        int h = 0x811C9DC5;
        for (int i = 0; i < id.length(); i++) {
            h = (h ^ id.charAt(i)) * 0x01000193;
        }
        // 序号只差最后几位,要把它们混开才五五开(MurmurHash3 的收尾)
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return (h & 1) == 0 ? Split.DEV : Split.HELD;
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("region", region);
        o.addProperty("bucket", bucket.label);
        o.addProperty("distance", Math.round(distance * 10) / 10.0);
        o.add("from", cell(from));
        JsonObject goal = new JsonObject();
        goal.addProperty("type", goalType.name().toLowerCase());
        goal.add("to", cell(to));
        if (goalType == GoalType.NEAR) {
            goal.addProperty("radius", radius);
        }
        o.add("goal", goal);
        o.addProperty("endClass", endClass.name().toLowerCase());
        o.addProperty("split", split.name().toLowerCase());
        JsonArray tagArray = new JsonArray();
        tags.forEach(t -> tagArray.add(t.label));
        o.add("tags", tagArray);
        return o;
    }

    static Problem fromJson(JsonObject o) {
        JsonObject goal = o.getAsJsonObject("goal");
        List<Tag> tags = new ArrayList<>();
        o.getAsJsonArray("tags").forEach(t -> tags.add(Tag.named(t.getAsString())));
        return new Problem(o.get("id").getAsString(), o.get("region").getAsString(), Bucket.named(o.get("bucket").getAsString()),
                o.get("distance").getAsDouble(), cell(o.getAsJsonArray("from")), GoalType.valueOf(goal.get("type").getAsString().toUpperCase()),
                cell(goal.getAsJsonArray("to")), goal.has("radius") ? goal.get("radius").getAsInt() : 0,
                EndClass.valueOf(o.get("endClass").getAsString().toUpperCase()), Split.valueOf(o.get("split").getAsString().toUpperCase()), tags);
    }

    static JsonArray cell(BlockPos pos) {
        JsonArray a = new JsonArray();
        a.add(pos.getX());
        a.add(pos.getY());
        a.add(pos.getZ());
        return a;
    }

    static BlockPos cell(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }
}
