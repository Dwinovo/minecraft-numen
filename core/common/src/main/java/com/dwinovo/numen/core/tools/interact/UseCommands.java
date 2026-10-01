package com.dwinovo.numen.core.tools.interact;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.EntityRef;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.core.tools.ContainerOps;
import com.dwinovo.numen.core.tools.GuiOps;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.core.tools.SleepOps;
import com.dwinovo.numen.task.TaskDispatch;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * {@code use}:像玩家那样点世界——对一格按鼠标键、对着前方用手里的东西、对一只实体按鼠标键,看打开的界面、在里面搬东西、
 * 关掉它,上床。
 *
 * <p>三个按键动作与两个搬东西的动作是有界短活({@code runSync}),动手前各自把动作交给权限层;看界面、关界面、上床当场回。
 * 搬东西一次一步:{@code transfer} 放到指定的一格,{@code shift} 像按住 Shift 点它、整叠挪到另一边——"不给目标格就是另一件事"
 * 拆成两个动作,一个动作一个意思;要搬好几样就同一轮发好几行。
 * 对准一格和不对准任何东西是两件事,拆成 {@code block} 与 {@code ahead} 两个动作,一个动作一个意思。
 * 上床放在这一组:原版里睡觉就是用一张床,和别的"用"是同一种动作,找床与走过去仍归扫描和 {@code move goto}。都不提升成快捷工具。
 *
 * <p>按哪个键是一个开关:默认右键(用、放、开),{@code --left} 是左键(打、挖)。照命令行的习惯,两个值里有一个是常用的那个时,
 * 不写一个必须给值的 {@code --button},而是让常用的那个当默认、另一个是开关——右键是"用"这一组的本义,点一格、点一只实体十回里
 * 九回是右键,左键是例外,所以名字是那个例外。按住多久照命令行的单位写秒({@code --hold 1.5})。
 */
public final class UseCommands {

    static final String GROUP = "use";
    static final String BLOCK = "block";
    static final String AHEAD = "ahead";
    static final String ENTITY = "entity";
    static final String GUI = "gui";
    static final String TRANSFER = "transfer";
    static final String SHIFT = "shift";
    static final String CLOSE = "close";
    static final String SLEEP = "sleep";

    /** 按住至多几秒。 */
    private static final double MAX_HOLD_S = 60;

    private static final Param<Boolean> LEFT = Param.optional("left", ArgType.bool(),
            "Press the left button instead: attack or break.")
            .whenOmitted("press the right button: use, activate, place or throw");
    private static final Param<BlockPos> AIM = Param.required("cell", ArgType.cell(), "The cell to aim at.");
    private static final Param<EntityRef> TARGET = Param.required("entity", ArgType.entity(), "The entity to act on.")
            .values("an entity id from scan entities");
    private static final Param<Double> HOLD = Param.optional("hold", ArgType.number(0.05, MAX_HOLD_S),
            "How long to hold the button, in seconds; the press ends early once the action completes.")
            .whenOmitted("press once");
    private static final Param<ResourceLocation> ITEM = Param.optional("item", ArgType.id(),
            "An item from your inventory to take in hand first, e.g. minecraft:bone_meal.")
            .whenOmitted("use what you hold");
    private static final Param<Boolean> SNEAK = Param.optional("sneak", ArgType.bool(),
            "Hold sneak while pressing, as a player holds Shift and clicks; while riding, that steps you off "
                    + "first.")
            .whenOmitted("press standing");
    private static final Param<BlockPos> BED = Param.optional("at", ArgType.cell(), "The bed.")
            .whenOmitted("use whichever bed is in reach");

    private static final Param<Integer> FROM = Param.required("from", ArgType.integer(0, 999),
                    "The slot to take the items from.")
            .values("a slot index from `use gui`");
    private static final Param<Integer> TO = Param.required("to", ArgType.integer(0, 999),
                    "The slot to put them in: an empty slot takes them, the same item merges, a different item swaps "
                            + "places with them.")
            .values("a slot index from `use gui`");
    private static final Param<Integer> COUNT = Param.optional("count", ArgType.integer(1, 99),
                    "How many to move; needs an empty slot or the same item there.")
            .whenOmitted("move the whole stack");

    private static final BlockActionOps CLICKS = new BlockActionOps();
    private static final GuiOps GUIS = new GuiOps();
    private static final SleepOps BEDS = new SleepOps();

    private UseCommands() {}

    static String line(String action) {
        return GROUP + " " + action;
    }

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Clicking the world like a player: blocks, entities, the open GUI, beds.",
                UseCommands::actions);
    }

    private static void actions(CommandGroup use) {
        use.server(BLOCK, "Aim at a block, fluid or air cell within reach and press a mouse button: the full "
                        + "native click.",
                UseCommands::block, AIM, LEFT, HOLD, ITEM, SNEAK)
                .example(line(BLOCK) + " 120 64 -35")
                .example(line(BLOCK) + " 120 63 -35 --item minecraft:bucket")
                .example(line(BLOCK) + " 120 64 -35 --item minecraft:oak_planks --sneak")
                .example(line(BLOCK) + " 120 64 -35 --left")
                .note("If the aimed block doesn't take a right click, the held item acts on its own, exactly like "
                        + "a real right-click: aiming at water with a bucket scoops it, with a boat places it.")
                .note("With --sneak and something in hand, a right click skips what the aimed block itself "
                        + "does: a block goes onto a chest instead of opening it.")
                .note("It does NOT travel: you must already be within working reach (~4.5 blocks) of the aim "
                        + "point; `move goto 120 64 -35 --arrive use` with its coordinates stands you where one of its faces is in sight and in "
                        + "reach. Farther away it fails and names that move goto.")
                .note("Both buttons are bare key presses: whatever you hold is what is used, and the block the "
                        + "crosshair lands on is the one clicked — if something else is in the way (tall grass in front "
                        + "of a chest, a leaf), that is what gets clicked, and the result says so and names the next "
                        + "step: `work dig` the thing in the way, or click from another side. It never moves, never "
                        + "swaps tools, never clears the way. --left holds the button until the block breaks or "
                        + "--hold runs out. To dig something out properly — best tool, the way cleared — use work dig.")
                .note("Breaking or placing near your owner's things may ask your owner first; the call waits for the "
                        + "answer.")
                .note("The result reports what actually changed (hands, the aimed block, new entities); no "
                        + "change listed means the click did nothing.")
                .seeAlso(line(GUI), line(ENTITY));
        use.server(AHEAD, "Press a mouse button at nothing in particular: the held item acts straight ahead, "
                        + "where you face.",
                UseCommands::ahead, LEFT, HOLD, ITEM, SNEAK)
                .example(line(AHEAD) + " --item minecraft:snowball")
                .note("To aim somewhere, `" + line(BLOCK) + "` at that cell instead; air cells work too.")
                .note("Food and drink go through `inv eat`, not here.")
                .seeAlso(line(BLOCK));
        use.server(ENTITY, "Press a mouse button on an entity: walk up to it, follow it, and act once your "
                        + "crosshair reaches it.",
                UseCommands::entity, TARGET, LEFT, HOLD, ITEM, SNEAK)
                .example(line(ENTITY) + " 812 --item minecraft:shears")
                .example(line(ENTITY) + " 812 --left")
                .example(line(ENTITY) + " 812 --sneak")
                .note("A wall in the way makes you re-position, not hit through it.")
                .note("Right on a boat or rideable boards it: runtime_state then shows <riding>; move goto pilots or "
                        + "steps off. Never click your own vehicle again.")
                .note("Hitting pets, named mobs or villagers asks your owner first; the call waits for the answer.")
                .seeAlso(line(BLOCK));
        use.server(GUI, "Look at the GUI you have open, or at your own inventory menu when none is.",
                UseCommands::gui, Listing.PAGE)
                .example(line(GUI))
                .note("Instant and read-only. Lists every slot (index, side, item and count, [output] mark), "
                        + "the cursor and any machine progress; a crafting grid is drawn as a 2D map of slot "
                        + "numbers.")
                .note("With nothing open it shows YOUR inventory menu, whose 2x2 grid crafts small recipes "
                        + "without a table.")
                .note("A modded GUI with very many slots comes a page at a time; the last line says how to get the "
                        + "next.")
                .note("Read slot indices here before `use transfer` or `use shift`, and to check one. Before laying "
                        + "a recipe out by hand, `inv recipe` gives the exact layout: match it onto the map cell for "
                        + "cell (a smaller recipe sits top-left); 2x2 slot indices are easy to guess wrong.")
                .seeAlso(line(BLOCK), line(TRANSFER), line(SHIFT), line(CLOSE), "inv recipe");
        use.server(TRANSFER, "Move items from one slot of the GUI you have open to another: move, merge or swap.",
                        UseCommands::transfer, FROM, TO, COUNT)
                .example(line(TRANSFER) + " 38 1 --count 1")
                .example(line(TRANSFER) + " 12 40")
                .note("One move per line. To move several stacks, run several lines in the same turn; they run in "
                        + "order, and each result says what moved.")
                .note("Taking something out of a container may ask your owner first; the line waits for the answer.")
                .note("To send a whole stack to the other side (into the chest, back to your inventory, into a "
                        + "furnace's input or fuel slot), `" + line(SHIFT) + "` it instead of picking a slot.")
                .seeAlso(line(GUI), line(SHIFT));
        use.server(SHIFT, "Shift-click a slot of the GUI you have open: its whole stack goes to the other side.",
                        UseCommands::shift, FROM)
                .example(line(SHIFT) + " 5")
                .note("The menu picks where it lands, like a real shift-click: a chest's items go to your inventory "
                        + "and yours into the chest, raw iron into a furnace's input and coal into its fuel slot.")
                .note("On a crafting result it takes the result, crafting again while the grid still holds enough.")
                .note("Taking something out of a container may ask your owner first; the line waits for the answer.")
                .seeAlso(line(GUI), line(TRANSFER));
        use.server(CLOSE, "Close the GUI you have open, once you have finished moving items.",
                UseCommands::close)
                .example(line(CLOSE))
                .note("Instant. Your own inventory menu is always there; with nothing else open there is nothing "
                        + "to close.")
                .seeAlso(line(GUI));
        use.server(SLEEP, "Get into a bed you are standing next to, and say whether you are actually asleep.",
                UseCommands::sleep, BED)
                .example(line(SLEEP))
                .example(line(SLEEP) + " --at 120 64 -35")
                .note("It does NOT travel: find a bed with `scan blocks #minecraft:beds` (that one tag covers "
                        + "every colour), `move goto 120 64 -35 --arrive use` with its coordinates, then run this.")
                .note("Succeeds only when the server confirms you are sleeping; otherwise it hands back "
                        + "Minecraft's own reason. \"Only at night\" means wait (`task timer`), not retry; \"too far "
                        + "away\" means move goto.")
                .note("Returns the moment you lie down; night passes on its own.")
                .seeAlso("task timer");
    }

    private static void block(ServerSource src, CommandArgs args) {
        click(src, args, args.get(AIM));
    }

    private static void ahead(ServerSource src, CommandArgs args) {
        click(src, args, null);
    }

    /** 对一格按,或({@code aim} 为 null)朝着她面对的方向按:同一件活,只差瞄哪儿。 */
    private static void click(ServerSource src, CommandArgs args, BlockPos aim) {
        TaskDispatch.runSync(src.companion(), CLICKS.interactAt(src, button(args), aim, holdTicks(args),
                idOf(args.get(ITEM)), Boolean.TRUE.equals(args.get(SNEAK))), src::reply);
    }

    /** 点名的那只按运行期编号认;按 UUID 写的(重放)换成它此刻的编号,不在了照编号交给任务,由任务如实说它不在。 */
    private static void entity(ServerSource src, CommandArgs args) {
        EntityRef ref = args.get(TARGET);
        Entity found = ref.in(src.companion().serverLevel());
        int id = ref.id() != null ? ref.id() : found != null ? found.getId() : -1;
        TaskDispatch.runSync(src.companion(), CLICKS.interactEntity(src, button(args), id, holdTicks(args),
                idOf(args.get(ITEM)), Boolean.TRUE.equals(args.get(SNEAK))), src::reply);
    }

    private static MouseButton button(CommandArgs args) {
        return Boolean.TRUE.equals(args.get(LEFT)) ? MouseButton.LEFT : MouseButton.RIGHT;
    }

    /** {@code --hold} 的秒数折成刻,至少一刻;不写是 0,按一下。 */
    private static int holdTicks(CommandArgs args) {
        Double seconds = args.get(HOLD);
        return seconds == null ? 0 : (int) Math.max(1, Math.round(Math.min(seconds, MAX_HOLD_S) * 20));
    }

    private static void gui(ServerSource src, CommandArgs args) {
        src.reply(GUIS.inspectGui(src.companion(), args, line(GUI)));
    }

    /** 有界短活:点击一刻就完,从容器里拿东西的那一步可能挂着等主人。 */
    private static void transfer(ServerSource src, CommandArgs args) {
        TaskDispatch.runSync(src.companion(), ContainerOps.transfer(src,
                new ContainerOps.Move(args.get(FROM), args.get(TO), args.get(COUNT))), src::reply);
    }

    private static void shift(ServerSource src, CommandArgs args) {
        TaskDispatch.runSync(src.companion(), ContainerOps.transfer(src,
                new ContainerOps.Move(args.get(FROM), null, null)), src::reply);
    }

    private static void close(ServerSource src, CommandArgs args) {
        src.reply(GUIS.closeGui(src.companion()));
    }

    /** 当场回:上床是一次调用的事,躺下就结束,不挂着等天亮。 */
    private static void sleep(ServerSource src, CommandArgs args) {
        src.reply(BEDS.sleep(args.get(BED), src.companion()));
    }

    private static String idOf(ResourceLocation id) {
        return id == null ? null : id.toString();
    }
}
