package com.dwinovo.numen.core.tools.interact;

import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.CellOrEntity;
import com.dwinovo.numen.cli.EntityRef;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.core.tools.GuiOps;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.core.tools.SleepOps;
import com.dwinovo.numen.task.TaskDispatch;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * {@code use}:像玩家那样点世界——右键一格(打开它就交回那个界面)、对着前方用手里的东西、右键一只实体、左键点一格或一只实体一下,
 * 上床。打开之后在界面里搬东西是 {@code gui} 那一组。
 *
 * <p>按键动作是有界短活({@code runSync}),动手前各自把动作交给权限层;上床当场回。按键动作都站在原地按:目标得在手够得着、
 * 看得见的地方,不然当场失败,说清先走过去({@code numen.move.to})。
 * 对准一格和不对准任何东西是两件事,拆成 {@code block} 与 {@code item} 两个动作,一个动作一个意思。右键是"用"这一组的本义,
 * 左键一下是 {@code hit};把一格挖下来是 {@code numen.work.dig}(挑工具、清挡路的),不在这一组。
 * 上床放在这一组:原版里睡觉就是用一张床,和别的"用"是同一种动作,找床与走过去仍归扫描和 {@code numen.move.to}。
 */
public final class UseCommands {

    static final String GROUP = "use";
    static final String BLOCK = "block";
    static final String ITEM_ACTION = "item";
    static final String ENTITY = "entity";
    static final String HIT = "hit";
    static final String SLEEP = "sleep";

    /** 按住至多几秒。 */
    private static final double MAX_HOLD_S = 60;

    private static final Param<BlockPos> AIM = Param.required("cell", ArgType.cell(), "The cell to aim at.");
    private static final Param<EntityRef> TARGET = Param.required("entity", ArgType.entity(), "The entity to act on.")
            .values("an entity id from numen.scan.entities");
    private static final Param<CellOrEntity> STRUCK = Param.required("target", ArgType.cellOrEntity(),
            "What to hit: a cell (a Pos, a Block) or an entity.");
    private static final Param<Double> HOLD = Param.optional("hold", ArgType.number(0.05, MAX_HOLD_S),
            "How long to hold the button, in seconds; the press ends early once the action completes.")
            .whenOmitted("press once");
    private static final Param<ResourceLocation> ITEM = Param.optional("item", ArgType.id(),
            "An item from your inventory to take in hand first, e.g. minecraft:bone_meal.")
            .whenOmitted("use what you hold");
    private static final Param<Boolean> SNEAK = Param.optional("sneak", ArgType.bool(),
            "Hold sneak while pressing, as a player holds Shift and clicks; while riding, that steps you off "
                    + "first (numen.move.dismount() does just that).")
            .whenOmitted("press standing");
    private static final Param<BlockPos> BED = Param.optional("at", ArgType.cell(), "The bed.")
            .whenOmitted("use whichever bed is in reach");

    private static final BlockActionOps CLICKS = new BlockActionOps();
    private static final SleepOps BEDS = new SleepOps();

    private UseCommands() {}

    /** 帮助里点名这一组的一个动作:{@code use block}。 */
    static String line(String action) {
        return GROUP + " " + action;
    }

    /** 按一下的结果。 */
    private static final ScriptType CLICKED = ScriptType.table(
                        ScriptType.field("button", ScriptType.choice(java.util.List.of("left", "right")), null),
                        ScriptType.optional("aim", Shapes.POS.type(), "The cell aimed at."),
                        ScriptType.optional("block", Shapes.BLOCK.type(), "The block the click used, when it opened "
                                + "or worked a station."),
                        ScriptType.optional("changes", ScriptType.listOf(ScriptType.STRING), "What changed: hands, the "
                                + "block, new entities."));

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Clicking the world like a player: right-click a block (opening its window) or "
                + "an entity, use what you hold, hit something once, get into bed.", UseCommands::actions);
    }

    private static void actions(CommandGroup use) {
        use.server(BLOCK, "Aim at a block, fluid or air cell within reach and right-click it: the full native click. "
                        + "When it opens a window (a chest, a furnace, a machine) it returns that Window.",
                UseCommands::block, AIM, HOLD, ITEM, SNEAK)
                .returns(ScriptType.union(GuiOps.WINDOW.type(), CLICKED))
                .example("local w = numen.use.block({x = 120, y = 64, z = -35})")
                .example("numen.use.block({x = 120, y = 63, z = -35}, {item = \"minecraft:bucket\"})")
                .example("numen.use.block({x = 120, y = 64, z = -35}, {item = \"minecraft:oak_planks\", sneak = true})")
                .note("When the click opens a window, it returns the Window, the same as numen.gui.view(): its "
                        + "methods put, take, move, quick and close work on it (see the numen.gui group).")
                .note("If the aimed block doesn't take a right click, the held item acts on its own, exactly like "
                        + "a real right-click: aiming at water with a bucket scoops it, with a boat places it.")
                .note("With sneak = true and something in hand, a right click skips what the aimed block itself "
                        + "does: a block goes onto a chest instead of opening it.")
                .note("It does NOT travel: you must already be within working reach (~4.5 blocks) of the aim "
                        + "point; `numen.move.to({x = 120, y = 64, z = -35}, {arrive = \"use\"})` stands you where one of its faces "
                        + "is in sight and in reach. Farther away it fails and names that call.")
                .note("It is a bare key press: whatever you hold is what is used, and the block the crosshair lands "
                        + "on is the one clicked — if something else is in the way (tall grass in front of a chest, a "
                        + "leaf), that is what gets clicked, and the result says so and names the next step. It never "
                        + "moves, never swaps tools, never clears the way.")
                .note("Placing near your owner's things may ask your owner first; the call waits for the answer.")
                .note("Otherwise the result reports what actually changed (hands, the aimed block, new entities); no "
                        + "change listed means the click did nothing.")
                .seeAlso("gui view", line(HIT), line(ENTITY));
        use.server(ITEM_ACTION, "Right-click at nothing in particular: the held item acts straight ahead, where you "
                        + "face.",
                UseCommands::item, HOLD, ITEM, SNEAK)
                .returns(CLICKED)
                .example("numen.use.item({item = \"minecraft:snowball\"})")
                .note("To aim somewhere, `numen.use.block` at that cell instead; air cells work too.")
                .note("Food and drink go through `numen.inv.eat`, not here.")
                .seeAlso(line(BLOCK));
        use.server(ENTITY, "Right-click an entity within reach and in sight of where you stand.",
                UseCommands::entity, TARGET, HOLD, ITEM, SNEAK)
                .returns(ScriptType.table(
                        ScriptType.field("button", ScriptType.choice(java.util.List.of("left", "right")), null),
                        ScriptType.field("entity_id", ScriptType.INTEGER, null),
                        ScriptType.optional("changes", ScriptType.listOf(ScriptType.STRING), "What changed.")))
                .example("numen.use.entity(812, {item = \"minecraft:shears\"})")
                .example("numen.use.entity(812, {sneak = true})")
                .note("It does NOT travel: an entity farther than your reach, or behind a wall, fails with where it "
                        + "is and the numen.move.to call to copy. numen.scan.entities gives its cell.")
                .note("Right on a boat or rideable boards it: runtime_state then shows <riding>; a plan with mode = "
                        + "\"boat\" steers it, numen.move.dismount() steps off. Never click your own vehicle again.")
                .seeAlso(line(BLOCK), line(HIT));
        use.server(HIT, "Left-click a cell or an entity within reach once, with what you hold.",
                UseCommands::hit, STRUCK)
                .returns(CLICKED)
                .example("numen.use.hit({x = 120, y = 64, z = -35})")
                .example("numen.use.hit(812)")
                .note("One press, never held: a block that takes several hits to break is only hit once — "
                        + "`numen.work.dig` breaks blocks (best tool, the way cleared); `numen.fight.attack` fights.")
                .note("It does NOT travel: the target must be within reach and in sight of where you stand; "
                        + "otherwise it fails with the numen.move.to call to copy.")
                .note("Hitting your owner's blocks, pets, named mobs or villagers asks your owner first; the call "
                        + "waits for the answer.")
                .seeAlso(line(BLOCK), line(ENTITY));
        use.server(SLEEP, "Get into a bed you are standing next to, and say whether you are actually asleep.",
                UseCommands::sleep, BED)
                .returns(ScriptType.table(ScriptType.field("bed", Shapes.POS.type(), "The bed's head."),
                        ScriptType.field("sleeping", ScriptType.BOOLEAN, null)))
                .example("numen.use.sleep()")
                .example("numen.use.sleep({at = {x = 120, y = 64, z = -35}})")
                .note("It does NOT travel: find a bed with `numen.scan.blocks(\"#minecraft:beds\")` (that one tag covers "
                        + "every colour), `numen.move.to({x = 120, y = 64, z = -35}, {arrive = \"use\"})` with its coordinates, then "
                        + "call this.")
                .note("Succeeds only when the server confirms you are sleeping; otherwise it hands back "
                        + "Minecraft's own reason. \"Only at night\" means wait (`numen.task.timer`), not retry; \"too far "
                        + "away\" means numen.move.to.")
                .note("Returns the moment you lie down; night passes on its own.")
                .seeAlso("task timer");
    }

    private static void block(ServerSource src, CommandArgs args) {
        click(src, args, args.get(AIM));
    }

    private static void item(ServerSource src, CommandArgs args) {
        click(src, args, null);
    }

    /** 对一格右键,或({@code aim} 为 null)朝着她面对的方向右键:同一件活,只差瞄哪儿。 */
    private static void click(ServerSource src, CommandArgs args, BlockPos aim) {
        TaskDispatch.runSync(src.companion(), CLICKS.interactAt(src, MouseButton.RIGHT, aim, holdTicks(args),
                idOf(args.get(ITEM)), Boolean.TRUE.equals(args.get(SNEAK))), src::reply);
    }

    /** 点名的那只按运行期编号认;按 UUID 写的(重放)换成它此刻的编号,不在了照编号交给任务,由任务如实说它不在。 */
    private static void entity(ServerSource src, CommandArgs args) {
        TaskDispatch.runSync(src.companion(), CLICKS.interactEntity(src, MouseButton.RIGHT, idOf(src, args.get(TARGET)),
                holdTicks(args), idOf(args.get(ITEM)), Boolean.TRUE.equals(args.get(SNEAK))), src::reply);
    }

    /** 左键一下:一格是按一下就松开的那种点,一只实体同样。 */
    private static void hit(ServerSource src, CommandArgs args) {
        CellOrEntity target = args.get(STRUCK);
        TaskDispatch.runSync(src.companion(), target.cell() != null
                ? CLICKS.interactAt(src, MouseButton.LEFT, target.cell(), 0, null, false)
                : CLICKS.interactEntity(src, MouseButton.LEFT, idOf(src, target.entity()), 0, null, false), src::reply);
    }

    private static int idOf(ServerSource src, EntityRef ref) {
        Entity found = ref.in(src.companion().serverLevel());
        return ref.id() != null ? ref.id() : found != null ? found.getId() : -1;
    }

    /** {@code hold} 的秒数折成刻,至少一刻;不写是 0,按一下。 */
    private static int holdTicks(CommandArgs args) {
        Double seconds = args.get(HOLD);
        return seconds == null ? 0 : (int) Math.max(1, Math.round(Math.min(seconds, MAX_HOLD_S) * 20));
    }

    /** 当场回:上床是一次调用的事,躺下就结束,不挂着等天亮。 */
    private static void sleep(ServerSource src, CommandArgs args) {
        src.reply(BEDS.sleep(args.get(BED), src.companion()));
    }

    private static String idOf(ResourceLocation id) {
        return id == null ? null : id.toString();
    }
}
