package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.act.Drops;
import com.dwinovo.numen.core.task.dig.DigCompanionTask;
import com.dwinovo.numen.core.task.fish.FishTaskRecord;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Job;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Rest;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.sdk.Target;

import java.util.List;
import java.util.Optional;

/**
 * {@code numen.work}:采集类的活——挖方块、钓鱼。两个函数都站在原地干,占身体,进任务槽;开始不了的(手够得着的一格都没有、站的地方抛不进
 * 水……)受理之前就当场失败,判据在各自的任务里。
 *
 * <p>{@code dig} 只挖她站在原地手够得着的格,挡在前面的一并挖开,不走动、不捡,收工前等这一挖的掉落物落定、说清各去了哪
 * ({@link DigCompanionTask});{@code fish} 只钓,不走去岸边、不追战果。走到够得着的地方是 {@code numen.move.to(…, {arrive = "dig"})}
 * 的事;捡是库里的 {@code numen.work.collect}:原版玩家走近掉落物就捡起来,所以捡就是走到掉落物跟前——挖完捡的是挖的结果里还落在
 * 地上的那几件。组合交给脚本。
 */
public final class WorkApi {

    private WorkApi() {}

    public static void install(NumenApi numen) {
        numen.api("work", "Gathering where you stand: digging blocks within reach, fishing. numen.work.collect "
                + "(library) walks to the drops and picks them up.", WorkApi.class);
    }

    /** 挖哪几格、至多几格。 */
    public record Dig(@Doc("What to dig, as many as you like: a Block from a query is dug only while that cell still "
            + "holds that block; a Pos is dug whatever it holds, air and fluid skipped; a Cluster from "
            + "numen.scan.blocks is its blocks.") @Rest List<Target> blocks,
                      @Doc("How many cells to dig at most (up to " + BlockActionOps.MAX_DIG_COUNT + ").")
                      @Omitted("dig every cell of it within reach") Optional<Integer> count) {}

    /** 点名的几格读成格子的那一步在 {@link BlockActionOps#dig},够不够得着在任务受理之前的准备里判。 */
    @Fn("Dig the given blocks that are within reach of where you stand.")
    @Example("numen.work.dig({name = \"minecraft:iron_ore\", pos = {x = 120, y = 12, z = -35}}, "
            + "{name = \"minecraft:iron_ore\", pos = {x = 121, y = 12, z = -35}})")
    @Example("numen.work.dig({x = 120, y = 12, z = -35})")
    @Example("local r = numen.work.dig({{x = 120, y = 64, z = -35}, {x = 120, y = 65, z = -35}}, {count = 1})\n"
            + "print(r.dug, r.left, r.out_of_reach)")
    @Note("A cluster from `numen.scan.blocks` or a Block from `numen.scan.block` goes in as it is.")
    @Note("Digs only what your hand reaches from where you stand: it never walks and never picks up. Get within reach "
            + "first with `numen.move.to(cluster, {arrive = \"dig\"})` (it picks the spot that reaches the most cells), "
            + "dig, and pick up what it dropped: `local r = numen.work.dig({x = 120, y = 12, z = -35}); "
            + "numen.work.collect({items = r.drops})`; `numen.work.mine(cluster)` does all three until the cluster is "
            + "gone.")
    @Note("Before it returns it waits up to " + Drops.SETTLE_TICKS / 20 + " seconds for what this dig dropped to "
            + "settle, and says where each went: landed (and where), picked up (and by whom), destroyed (lava, fire, "
            + "a cactus …), fallen into the void, or still moving. Only what it dropped is followed, and only until it "
            + "settles.")
    @Note("Background work: before it starts it checks something within reach can be dug, harvested with your tools "
            + "and is allowed; when nothing is, it fails with kind out_of_reach (or denied, failed) and a hint with the "
            + "numen.move.to call to copy — no task starts and whatever you were doing goes on. It returns when the "
            + "job ends: how many cells it dug and how many are still out of reach, the nearest of them as a pos.")
    @Note("A block in the way of your hand is dug open too when it is natural terrain. A block in the way that needs "
            + "your owner's consent or that their rules forbid is not touched: the account names it, where it is and "
            + "why.")
    @Note("Takes the best tool for each block; only digs what your tools actually harvest, and says so when nothing "
            + "qualifies. Asks your owner before breaking a named block their rules want asked about; a refusal stops "
            + "the job with the reason.")
    @SeeAlso({"numen.move.to", "numen.work.collect", "numen.scan.blocks", "numen.task.stop"})
    public static Job<DigCompanionTask.Dug> dig(ServerCall call, Dig args) {
        return Job.of(BlockActionOps.dig(call, args.blocks(), args.count().orElse(null)));
    }

    /** 抛一竿:一次调用一竿,钓几条是程序里调几次。 */
    @Fn("Cast a fishing rod once from where you stand, wait for a bite and reel it in.")
    @Example("numen.work.fish()")
    @Example("for i = 1, 5 do numen.work.fish() end")
    @Note("Returns what came up on the line, minecraft:cod x1. Refused with the reason when you carry no fishing rod, "
            + "do not stand on dry ground, or have no open water to cast into from where you stand.")
    @Note("One cast per call: a cast that misses the water, hooks an entity or gets no bite in a minute fails and "
            + "says why; cast again by calling it again.")
    @Note("It never walks: stand on the shore first. The reel throws each catch to you; one that lands short lies on "
            + "the ground for `numen.work.collect()`.")
    @Note("A catch is one bite reeled in: fish, junk or treasure, with vanilla loot, rod wear and stats.")
    @SeeAlso({"numen.work.collect", "numen.task.stop"})
    public static Job<List<String>> fish(ServerCall call) {
        return Job.of(new FishTaskRecord(call));
    }
}
