package com.dwinovo.numen.core.tools.interact;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.core.tools.Clicks;
import com.dwinovo.numen.core.tools.SleepOps;
import com.dwinovo.numen.sdk.CellOrEntity;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;

import java.util.Optional;

/**
 * {@code numen.use}:像玩家那样点世界——右键一格(打开它就交回那个界面)、对着前方用手里的东西、右键一只实体、左键点一格或一只实体一下,
 * 上床。打开之后在界面里搬东西是 {@code gui} 那一组。
 *
 * <p>按键是有界短活(经 {@link ServerCall#sync}),动手前各自把动作交给权限层;上床当场回。按键都站在原地按:目标得在手够得着、看得见的
 * 地方,不然当场失败,说清先走过去({@code numen.move.to})。对准一格和不对准任何东西是两件事,拆成 {@code block} 与 {@code item}。右键是
 * "用"这一组的本义,左键一下是 {@code hit};把一格挖下来是 {@code numen.work.dig}。上床放在这一组:原版里睡觉就是用一张床。
 */
public final class UseApi {

    /** 按住至多几秒。 */
    private static final double MAX_HOLD_S = 60;

    private UseApi() {}

    public static void install(NumenApi numen) {
        numen.api("use", "Clicking the world like a player: right-click a block (opening its window) or an entity, use "
                + "what you hold, hit something once, get into bed.", UseApi.class);
    }

    /** 右键一格。 */
    public record Block(@Doc("The cell to aim at.") BlockPos cell,
                        @Doc("How long to hold the button, in seconds (up to 60); the press ends early once the action "
                                + "completes.") @Omitted("press once") Optional<Double> hold,
                        @Doc("An item from your inventory to take in hand first, e.g. minecraft:bone_meal.")
                        @Omitted("use what you hold") Optional<Item> item,
                        @Doc("Hold sneak while pressing, as a player holds Shift and clicks; while riding, that steps "
                                + "you off first (numen.move.dismount() does just that).")
                        @Omitted("press standing") Optional<Boolean> sneak) {}

    @Fn("Aim at a block, fluid or air cell within reach and right-click it: the full native click. When it opens a "
            + "window (a chest, a furnace, a machine) it returns that Window.")
    @Example("local w = numen.use.block({x = 120, y = 64, z = -35})")
    @Example("numen.use.block({x = 120, y = 63, z = -35}, {item = \"minecraft:bucket\"})")
    @Example("numen.use.block({x = 120, y = 64, z = -35}, {item = \"minecraft:oak_planks\", sneak = true})")
    @Note("When the click opens a window, it returns the Window, the same as numen.gui.view(): its methods put, take, "
            + "move, quick and close work on it (see the numen.gui group).")
    @Note("If the aimed block doesn't take a right click, the held item acts on its own, exactly like a real "
            + "right-click: aiming at water with a bucket scoops it, with a boat places it.")
    @Note("With sneak = true and something in hand, a right click skips what the aimed block itself does: a block "
            + "goes onto a chest instead of opening it.")
    @Note("It does NOT travel: you must already be within working reach (~4.5 blocks) of the aim point; "
            + "`numen.move.to({x = 120, y = 64, z = -35}, {arrive = \"use\"})` stands you where one of its faces is in "
            + "sight and in reach. Farther away it fails and names that call.")
    @Note("It is a bare key press: whatever you hold is what is used, and the block the crosshair lands on is the one "
            + "clicked — if something else is in the way (tall grass in front of a chest, a leaf), that is what gets "
            + "clicked, and the result says so and names the next step. It never moves, never swaps tools, never "
            + "clears the way.")
    @Note("Placing near your owner's things may ask your owner first; the call waits for the answer.")
    @Note("Otherwise the result reports what actually changed (your inventory and experience, health, riding, the aimed block, new entities); no change "
            + "listed means the click did nothing.")
    @SeeAlso({"numen.gui.view", "numen.use.hit", "numen.use.entity"})
    public static Pending<Clicks.Pressed> block(ServerCall call, Block args) {
        return call.sync(BlockActionOps.interactAt(call, MouseButton.RIGHT, args.cell(), holdTicks(args.hold()),
                args.item().orElse(null), args.sneak().orElse(false)));
    }

    /** 朝前方右键。 */
    public record Press(@Doc("How long to hold the button, in seconds (up to 60).") @Omitted("press once")
                        Optional<Double> hold,
                        @Doc("An item from your inventory to take in hand first.") @Omitted("use what you hold")
                        Optional<Item> item,
                        @Doc("Hold sneak while pressing.") @Omitted("press standing") Optional<Boolean> sneak) {}

    @Fn("Right-click at nothing in particular: the held item acts straight ahead, where you face.")
    @Example("numen.use.item({item = \"minecraft:snowball\"})")
    @Note("To aim somewhere, `numen.use.block` at that cell instead; air cells work too.")
    @Note("Food and drink go through `numen.inv.eat`, not here.")
    @SeeAlso("numen.use.block")
    public static Pending<Clicks.Clicked> item(ServerCall call, Press args) {
        return call.sync(BlockActionOps.interactAt(call, MouseButton.RIGHT, null, holdTicks(args.hold()),
                args.item().orElse(null), args.sneak().orElse(false)));
    }

    /** 右键一只实体。 */
    public record OnEntity(@Doc("The entity to act on: an Entity from numen.scan.entities, or its id.") EntityRef entity,
                           @Doc("How long to hold the button, in seconds (up to 60).") @Omitted("press once")
                           Optional<Double> hold,
                           @Doc("An item from your inventory to take in hand first.") @Omitted("use what you hold")
                           Optional<Item> item,
                           @Doc("Hold sneak while pressing.") @Omitted("press standing") Optional<Boolean> sneak) {}

    @Fn("Right-click an entity within reach and in sight of where you stand.")
    @Example("numen.use.entity(812, {item = \"minecraft:shears\"})")
    @Example("numen.use.entity(812, {sneak = true})")
    @Note("It does NOT travel: an entity farther than your reach, or behind a wall, fails with where it is and the "
            + "numen.move.to call to copy. numen.scan.entities gives its cell.")
    @Note("Right on a boat or rideable boards it: runtime_state then shows <riding>; a plan with mode = \"boat\" "
            + "steers it, numen.move.dismount() steps off. Never click your own vehicle again.")
    @SeeAlso({"numen.use.block", "numen.use.hit"})
    public static Pending<Clicks.EntityClicked> entity(ServerCall call, OnEntity args) {
        return call.sync(BlockActionOps.interactEntity(call, MouseButton.RIGHT, idOf(call, args.entity()),
                holdTicks(args.hold()), args.item().orElse(null), args.sneak().orElse(false)));
    }

    /** 左键点什么。 */
    public record Hit(@Doc("What to hit: a cell (a Pos, a Block) or an entity.") CellOrEntity target) {}

    /** 左键一下:一格是按一下就松开的那种点,一只实体同样。 */
    @Fn("Left-click a cell or an entity within reach once, with what you hold.")
    @Example("numen.use.hit({x = 120, y = 64, z = -35})")
    @Example("numen.use.hit(812)")
    @Note("One press, never held: a block that takes several hits to break is only hit once — `numen.work.dig` breaks "
            + "blocks (best tool, the way cleared); `numen.fight.attack` fights.")
    @Note("It does NOT travel: the target must be within reach and in sight of where you stand; otherwise it fails "
            + "with the numen.move.to call to copy.")
    @Note("Hitting your owner's blocks, pets, named mobs or villagers asks your owner first; the call waits for the "
            + "answer.")
    @SeeAlso({"numen.use.block", "numen.use.entity"})
    public static Pending<Clicks.Hit> hit(ServerCall call, Hit args) {
        CellOrEntity target = args.target();
        return target.cell() != null
                ? call.sync(BlockActionOps.interactAt(call, MouseButton.LEFT, target.cell(), 0, null, false))
                : call.sync(BlockActionOps.interactEntity(call, MouseButton.LEFT, idOf(call, target.entity()), 0, null,
                        false));
    }

    /** 点名的那只按运行期编号认;按 UUID 写的(重启后再跑)换成它此刻的编号,不在了照编号交给活,由活如实说它不在。 */
    private static int idOf(ServerCall call, EntityRef ref) {
        Entity found = ref.in(call.her().serverLevel());
        return ref.id() != null ? ref.id() : found != null ? found.getId() : -1;
    }

    /** 按住的秒数折成刻,至少一刻;不写是 0,按一下。 */
    private static int holdTicks(Optional<Double> seconds) {
        return seconds.map(s -> (int) Math.max(1, Math.round(Math.min(s, MAX_HOLD_S) * 20))).orElse(0);
    }

    /** 上哪张床。 */
    public record Sleep(@Doc("The bed.") @Omitted("use whichever bed is in reach") Optional<BlockPos> at) {}

    /** 当场回:上床是一次调用的事,躺下就结束,不挂着等天亮。 */
    @Fn("Get into a bed you are standing next to, and say whether you are actually asleep.")
    @Example("numen.use.sleep()")
    @Example("numen.use.sleep({at = {x = 120, y = 64, z = -35}})")
    @Note("It does NOT travel: find a bed with `numen.scan.blocks(\"#minecraft:beds\")` (that one tag covers every "
            + "colour), `numen.move.to({x = 120, y = 64, z = -35}, {arrive = \"use\"})` with its coordinates, then call "
            + "this.")
    @Note("Succeeds only when the server confirms you are sleeping; otherwise it hands back Minecraft's own reason. "
            + "\"Only at night\" means wait (`numen.task.timer`), not retry; \"too far away\" means numen.move.to.")
    @Note("Returns the moment you lie down; night passes on its own.")
    @SeeAlso("numen.task.timer")
    public static SleepOps.Slept sleep(ServerCall call, Sleep args) {
        return SleepOps.sleep(args.at().orElse(null), call.her());
    }
}
