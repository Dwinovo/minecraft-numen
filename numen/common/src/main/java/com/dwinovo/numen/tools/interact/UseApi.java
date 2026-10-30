package com.dwinovo.numen.tools.interact;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.task.MouseButton;
import com.dwinovo.numen.tools.BlockActionOps;
import com.dwinovo.numen.tools.Clicks;
import com.dwinovo.numen.tools.SleepOps;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.CellOrEntity;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.EntityRef;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.sdk.Rest;
import com.dwinovo.numen.tools.Signs;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code numen.use}:像玩家那样点世界——右键一格(打开它就交回那个界面)、对着前方用手里的东西、右键一只实体、左键点一格或一只实体一下,
 * 上床、从坐着的东西上下来。打开之后在界面里搬东西是 {@code gui} 那一组。
 *
 * <p>按键经 {@link ServerCall#sync},动手前各自把动作交给权限层;上床当场回。目标得在手够得着、看得见的地方才按,够不着先走到能按的
 * 地方({@code InReachTask} 的走过去,到达目标是动作自己那一处的)。对准一格和不对准任何东西是两件事,拆成 {@code block} 与
 * {@code item}。右键是"用"这一组的本义,左键一下是 {@code hit};把一格挖下来是 {@code numen.work.dig}。上床放在这一组:原版里睡觉就是
 * 用一张床。
 */
public final class UseApi {

    /** 按住至多几秒。 */
    private static final double MAX_HOLD_S = 60;

    private UseApi() {}

    public static void install(NumenApi numen) {
        numen.api("use", "Clicking the world like a player: right-click a block (opening its window) or an entity, use "
                + "what you hold, hit something once, get into bed, step off what you ride.", UseApi.class);
    }

    /** 右键一格。 */
    public record Block(@Doc("The cell to aim at.") BlockPos cell,
                        @Doc("How long to hold the button, in seconds (up to 60); the press ends early once the action "
                                + "completes.") @Omitted("press once") Optional<Double> hold,
                        @Doc("An item from your inventory to take in hand first, e.g. minecraft:bone_meal.")
                        @Omitted("use what you hold") Optional<Item> item,
                        @Doc("Hold sneak while pressing, as a player holds Shift and clicks; while riding, that steps "
                                + "you off first (numen.use.dismount() does just that).")
                        @Omitted("press standing") Optional<Boolean> sneak) {}

    @Fn("Aim at a block, fluid or air cell within reach and right-click it: the full native click. When it opens a "
            + "window (a chest, a furnace, a machine) it returns that Window.")
    @Example("local w = numen.use.block({x = 120, y = 64, z = -35})")
    @Example("numen.use.block({x = 120, y = 63, z = -35}, {item = \"minecraft:bucket\"})")
    @Example("numen.use.block({x = 120, y = 64, z = -35}, {item = \"minecraft:oak_planks\", sneak = true})")
    @Note("With item, the stack taken in hand is the one already in your main hand, else the first slot holding that item "
            + "(numen.inv.items lists stacks that differ, e.g. a shulker box with contents); the result says which. To use "
            + "another one, numen.gui.move it into your main hand's slot first.")
    @Note("When the click opens a window, it returns the Window, the same as numen.gui.view(): its methods put, take, "
            + "move, quick and close work on it (see the numen.gui group).")
    @Note("If the aimed block doesn't take a right click, the held item acts on its own, exactly like a real "
            + "right-click: aiming at water with a bucket scoops it, with a boat places it.")
    @Note("With sneak = true and something in hand, a right click skips what the aimed block itself does: a block "
            + "goes onto a chest instead of opening it.")
    @Note("Out of working reach (~4.5 blocks) of the aim point, it first walks to where one of its faces is in sight and in "
            + "reach, then clicks; the walk digs and builds its own way like `numen.move.to` and is told in the result. "
            + "A walk that can't get there fails with its reason and nothing is clicked.")
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
    public static Pending<Clicks.Pressed> item(ServerCall call, Press args) {
        return call.sync(BlockActionOps.interactAt(call, MouseButton.RIGHT, null, holdTicks(args.hold()),
                args.item().orElse(null), args.sneak().orElse(false)));
    }

    /** 右键一只实体。 */
    public record OnEntity(@Doc("The entity to act on: an Entity from numen.scan.entities, or its id (zombie@3f9a1c).") EntityRef entity,
                           @Doc("How long to hold the button, in seconds (up to 60).") @Omitted("press once")
                           Optional<Double> hold,
                           @Doc("An item from your inventory to take in hand first.") @Omitted("use what you hold")
                           Optional<Item> item,
                           @Doc("Hold sneak while pressing.") @Omitted("press standing") Optional<Boolean> sneak,
                           @Doc("Which part of an armor stand to click: head, chest, legs, feet, hand or offhand. With "
                                   + "an item in hand it goes to the slot the item belongs in; with an empty hand it takes "
                                   + "off what is on that part.")
                           @Omitted("click the entity itself") Optional<StandPart> at) {}

    @Fn("Right-click an entity within reach and in sight of where you stand. When it opens a window (a villager's trade) "
            + "it returns that Window.")
    @Example("numen.use.entity(\"sheep@3f9a1c\", {item = \"minecraft:shears\"})")
    @Example("numen.use.entity(\"villager@3f9a1c\", {sneak = true})")
    @Example("numen.use.entity(\"armor_stand@3f9a1c\", {item = \"minecraft:iron_helmet\", at = \"head\"})")
    @Note("An entity farther than your reach, or behind a wall, is walked up to first, then clicked; the walk is told in "
            + "the result and a walk that can't get there fails with its reason.")
    @Note("Right on a boat or rideable boards it: runtime_state then shows <riding>; `numen.move.to` sails a boat "
            + "across water, numen.use.dismount() steps off. Never click your own vehicle again.")
    @Note("An armor stand takes its gear by where you click (`at`), like a real client's click on that spot: the click on the "
            + "spot goes first, a plain click after it. Minecraft decides what fits where; the result lists what changed on the "
            + "stand and in your hands.")
    @SeeAlso({"numen.use.block", "numen.use.hit"})
    public static Pending<Clicks.Pressed> entity(ServerCall call, OnEntity args) {
        EntityRef target = present(call, args.entity());
        Vec3 at = null;
        if (args.at().isPresent()) {
            if (!(call.entity(target) instanceof ArmorStand stand)) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "at names a part of an armor stand, and " + target + " is not one",
                        Call.of("numen.use.entity", target));
            }
            at = args.at().get().pointOn(stand);
        }
        return call.sync(BlockActionOps.interactEntity(call, MouseButton.RIGHT, target,
                holdTicks(args.hold()), args.item().orElse(null), args.sneak().orElse(false), at));
    }

    /** 左键点什么。 */
    public record Hit(@Doc("What to hit: a cell (a Pos, a Block) or an entity.") CellOrEntity target) {}

    /** 左键一下:一格是按一下就松开的那种点,一只实体同样。 */
    @Fn("Left-click a cell or an entity within reach once, with what you hold.")
    @Example("numen.use.hit({x = 120, y = 64, z = -35})")
    @Example("numen.use.hit(\"pig@3f9a1c\")")
    @Note("One press, never held: a block that takes several hits to break is only hit once — `numen.work.dig` breaks "
            + "blocks (best tool, the way cleared); `numen.fight.attack` fights.")
    @Note("A target out of reach or out of sight is walked up to first, then hit; a walk that can't get there fails "
            + "with its reason and nothing is hit.")
    @Note("Hitting your owner's blocks, pets, named mobs or villagers asks your owner first; the call waits for the "
            + "answer.")
    @SeeAlso({"numen.use.block", "numen.use.entity"})
    public static Pending<Clicks.Pressed> hit(ServerCall call, Hit args) {
        CellOrEntity target = args.target();
        return target.cell() != null
                ? call.sync(BlockActionOps.interactAt(call, MouseButton.LEFT, target.cell(), 0, null, false))
                : call.sync(BlockActionOps.interactEntity(call, MouseButton.LEFT, present(call, target.entity()), 0, null,
                        false, null));
    }

    /** 点名的那只此刻得在附近:不在当场失败,不先受理再失败。 */
    private static EntityRef present(ServerCall call, EntityRef ref) {
        return EntityRef.of(call.entity(ref));
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
            + "colour), `numen.move.to({x = 120, y = 64, z = -35}, 2)` with its coordinates, then call this.")
    @Note("Succeeds only when the server confirms you are sleeping; otherwise it hands back Minecraft's own reason. "
            + "\"Only at night\" means wait (`numen.task.timer`), not retry; \"too far away\" means numen.move.to.")
    @Note("Returns the moment you lie down; night passes on its own.")
    @SeeAlso("numen.task.timer")
    public static SleepOps.Slept sleep(ServerCall call, Sleep args) {
        return SleepOps.sleep(args.at().orElse(null), call.her());
    }

    /** {@code numen.use.dismount} 交回的值。 */
    @Doc("What you stepped off.")
    public record Dismounted(@Doc("What you stepped off, minecraft:oak_boat.") String vehicle,
                             @Doc("Where you stand now.") Vec3 pos) {}

    /**
     * 下来:原版玩家按潜行就从坐着的东西上下来(服务端在骑乘刻里调 {@code stopRiding}),落脚点由原版的下车位置定。这里直接调同一个
     * {@code stopRiding},结果一样,当场就有;下来了什么、站在哪照实交回。上载具是 {@code use.entity},下载具同属 {@code use}。
     */
    @Fn("Step off the boat, minecart or mount you ride, as a player does with sneak.")
    @Example("numen.use.dismount()")
    @Note("Instant. Riding nothing fails with kind failed. Vanilla puts you down beside it, where there is room.")
    @SeeAlso("numen.use.entity")
    public static Dismounted dismount(ServerCall call) {
        NumenPlayer her = call.her();
        Entity vehicle = her.getVehicle();
        if (vehicle == null) {
            throw new ApiError(ErrorKind.FAILED, "I am not riding anything", null);
        }
        String what = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString();
        her.stopRiding();
        return new Dismounted(what, her.position());
    }

    /** {@code numen.use.wake} 交回的值。 */
    @Doc("Where you woke.")
    public record Woke(@Doc("Where you stand now.") Vec3 pos) {}

    /** 起床:发的是客户端点"离开床"发的那个包,原版自己把她放回床边;读回来看她还睡不睡。 */
    @Fn("Get out of the bed you sleep in.")
    @Example("numen.use.wake()")
    @Note("Instant; the pair of numen.use.sleep. Not being asleep fails with kind failed.")
    @SeeAlso("numen.use.sleep")
    public static Woke wake(ServerCall call) {
        NumenPlayer her = call.her();
        if (!her.isSleeping()) {
            throw new ApiError(ErrorKind.FAILED, "I am not asleep", null);
        }
        her.packets().command(ServerboundPlayerCommandPacket.Action.STOP_SLEEPING);
        if (her.isSleeping()) {
            throw new ApiError(ErrorKind.FAILED, "the server kept me in bed", null);
        }
        return new Woke(her.position());
    }

    /** {@code numen.use.glide} 交回的值。 */
    @Doc("Where you took off.")
    public record Glided(@Doc("Where you are.") Vec3 pos) {}

    /** 鞘翅起飞:发的是空中按跳跃键发的那个包,条件(在空中、不在水里、穿着没坏的鞘翅、没有漂浮效果)全由原版判。 */
    @Fn("Start gliding with the elytra you wear.")
    @Example("numen.use.glide()")
    @Note("Instant. Only works in the air (jump or fall off a height first) with an unbroken elytra worn "
            + "(`numen.gear.wear(\"minecraft:elytra\")`); otherwise it fails and says which is missing.")
    @Note("Once gliding, `numen.use.item({item = \"minecraft:firework_rocket\"})` boosts you forward in the air.")
    @SeeAlso({"numen.gear.wear", "numen.use.item"})
    public static Glided glide(ServerCall call) {
        NumenPlayer her = call.her();
        her.packets().command(ServerboundPlayerCommandPacket.Action.START_FALL_FLYING);
        if (!her.isFallFlying()) {
            java.util.List<String> why = new java.util.ArrayList<>();
            ItemStack chest = her.getItemBySlot(EquipmentSlot.CHEST);
            if (her.onGround()) {
                why.add("you are on the ground (gliding starts in the air)");
            }
            if (her.isInWater()) {
                why.add("you are in water");
            }
            if (her.hasEffect(MobEffects.LEVITATION)) {
                why.add("you have levitation");
            }
            if (!chest.is(Items.ELYTRA)) {
                why.add("you wear no elytra");
            } else if (!ElytraItem.isFlyEnabled(chest)) {
                why.add("your elytra is broken");
            }
            throw new ApiError(ErrorKind.FAILED, "did not start gliding: " + (why.isEmpty() ? "the server refused"
                    : String.join("; ", why)), Call.of("numen.gear.wear", "minecraft:elytra"));
        }
        return new Glided(her.position());
    }

    /** 写哪块牌子、写什么。 */
    public record Sign(@Doc("The sign's cell.") BlockPos cell,
                       @Doc("The lines, top to bottom, at most four; missing ones are left empty.") @Rest List<String> lines,
                       @Doc("Write the back of the sign.") @Omitted("write the front") Optional<Boolean> back) {}

    /** {@code numen.use.sign} 交回的值。 */
    @Doc("A sign written.")
    public record Signed(@Doc("The sign's cell.") BlockPos cell,
                         @Doc("front or back.") String side,
                         @Doc("The four lines now on that side.") List<String> lines) {}

    /**
     * 写告示牌:发的是客户端编辑完牌子发的那个包。刚放下的牌子服务端已认她是能编辑的人,直接发;别的没涂蜡的牌子先像玩家那样右键它
     * (走到够得着、过权限层),服务端才认她。能不能写(涂没涂蜡、是不是她在编辑)原版判,发完等原版写完读回来牌子上的字。
     */
    @Fn("Write or change the text of a sign.")
    @Example("numen.use.sign({x = 120, y = 64, z = -35}, {\"Home\", \"sweet home\"})")
    @Example("numen.use.sign({x = 120, y = 64, z = -35}, {\"Back side\"}, {back = true})")
    @Note("Replaces the whole side. A sign you just placed (`numen.use.block(cell, {item = \"minecraft:oak_sign\"})`) is "
            + "yours to write at once; any other sign that is not waxed is right-clicked first, walking up to it, so a "
            + "dye, ink sac or honeycomb in hand will colour or wax it instead.")
    @Note("Minecraft decides: a waxed sign (numen.use.block with an axe scrapes the wax) or one someone else is writing "
            + "cannot be written and the call fails saying so. Writing on someone's sign may ask your owner first.")
    @Note("The text on a sign is read with `numen.scan.block`.")
    @SeeAlso({"numen.scan.block", "numen.use.block"})
    public static Pending<Signed> sign(ServerCall call, Sign args) {
        NumenPlayer her = call.her();
        BlockPos pos = args.cell();
        SignBlockEntity sign = Signs.at(her, pos);
        if (args.lines().size() > 4) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a sign has four lines, not " + args.lines().size(), null);
        }
        boolean front = !args.back().orElse(false);
        if (her.getUUID().equals(sign.getPlayerWhoMayEdit())) {
            return call.authorize(Action.useBlock(pos, her.level().getBlockState(pos)))
                    .thenWait(ok -> write(her, pos, front, args.lines()));
        }
        return call.sync(BlockActionOps.interactAt(call, MouseButton.RIGHT, pos, 0, null, false))
                .thenWait(pressed -> write(her, pos, front, args.lines()));
    }

    /** 牌子认她是编辑的人了就发包写,发完等原版写完读回来;不认就说为什么。 */
    private static Pending<Signed> write(NumenPlayer her, BlockPos pos, boolean front, List<String> lines) {
        SignBlockEntity sign = Signs.at(her, pos);
        if (!her.getUUID().equals(sign.getPlayerWhoMayEdit())) {
            throw new ApiError(ErrorKind.FAILED, sign.isWaxed() ? "the sign is waxed, its text cannot be changed"
                    : "the sign did not open for editing: someone else is writing it, or you may not build here",
                    sign.isWaxed() ? Call.of("numen.use.block", pos, Map.of("item", "minecraft:iron_axe")) : null);
        }
        List<String> wanted = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            wanted.add(ChatFormatting.stripFormatting(i < lines.size() ? lines.get(i) : ""));
        }
        Pending<Signed> out = Pending.create();
        her.packets().signUpdate(pos, front, lines);
        her.packets().afterHandled(() -> {
            if (!(her.level().getBlockEntity(pos) instanceof SignBlockEntity now)) {
                out.fail(new ApiError(ErrorKind.NOT_FOUND, "the sign is gone", null));
                return;
            }
            List<String> shown = Signs.lines(now.getText(front));
            if (!shown.equals(wanted)) {
                out.fail(new ApiError(ErrorKind.FAILED, "the sign was not changed: it reads " + shown,
                        Call.of("numen.scan.block", pos)));
                return;
            }
            out.report("wrote the " + (front ? "front" : "back") + " of the sign at " + pos.getX() + "," + pos.getY() + ","
                    + pos.getZ() + ".");
            out.complete(new Signed(pos, front ? "front" : "back", shown));
        });
        return out;
    }
}
