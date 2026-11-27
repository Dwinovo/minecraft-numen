package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.EntityInfo;
import com.dwinovo.numen.api.sdk.EntityLabel;
import com.dwinovo.numen.api.sdk.EntityRef;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Flatten;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.sdk.StackRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code tlm.maid}:她养的女仆——名下有哪些、一只的详情、切工作模式、改设置、攻击名单、改名、换模型、递东西给她、从她身上拿回来。
 *
 * <p>都在服务端:女仆是世界里的实体。读的(清单、详情、攻击名单、模型)当场回、不问主人;做的是人在女仆界面里按的按钮,每一个都是用这只女仆
 * ({@link ServerCall#use}:够不够得着、权限层的 {@code use_entity}、放行后再认一次),放行了才调车万女仆的包({@link Maids})。和
 * {@code minecraft.use.block} 同一条规矩:不走路,够不着就失败并给出照抄就能走过去的那一次调用。
 *
 * <p>本类不碰车万女仆的类,只经 {@link Maids}:登记这一组(联动的检查只跑这一段)时那里没有车万女仆。
 */
public final class MaidApi {

    private MaidApi() {}

    /** 她的女仆在界面上够得着的距离,和车万女仆判界面还开不开着的同一个。 */
    private static final ServerCall.Reach GUI = (her, maid) -> Maids.inReach(her, maid);

    /** 日程:白天干活、夜里干活、全天干活。 */
    public enum Schedule { DAY, NIGHT, ALL }

    /** 女仆身上放东西的一处:她自己的格子与背包、两只手、四件盔甲、饰品栏。 */
    public enum Place { BACKPACK, MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET, BAUBLE }

    /** 她拾取什么:物品、经验,或两样都捡。 */
    public enum PickupKind { ITEM, XP, ALL }

    /** 攻击名单上对一种实体的态度:不打、被惹了才打、见了就打。 */
    public enum Stance { FRIENDLY, NEUTRAL, HOSTILE }

    /** 一只女仆在设置页「女仆配置」那一页上的八样。 */
    @Doc("The settings on the maid config page of her GUI.")
    public record Preferences(@Doc("Whether her backpack shows on her back.") boolean showBackpack,
                              @Doc("Whether the item she carries on her back shows.") boolean showBackItem,
                              @Doc("Whether her chat bubbles show.") boolean chatBubble,
                              @Doc("How often she speaks, 0 to 1.") double soundFrequency,
                              @Doc("What she picks up when picking up is on.") PickupKind pickupKind,
                              @Doc("Whether she opens doors.") boolean openDoor,
                              @Doc("Whether she opens fence gates.") boolean openFenceGate,
                              @Doc("Whether she climbs on her own.") boolean activeClimbing) {}

    /** 一只加载着的女仆。 */
    @Doc("A maid of Touhou Little Maid, loaded in the world: an Entity with her work and settings. Hand her on as she "
            + "is: tlm.maid.info(m), minecraft.use.entity(m), minecraft.move.walk(m).")
    public record Maid(@Flatten EntityInfo entity,
                       @Doc("The model she wears.") String model,
                       @Doc("Her work mode, touhou_little_maid:farm.") String task,
                       Schedule schedule,
                       @Doc("Home mode.") boolean home,
                       int favorabilityLevel,
                       boolean sitting,
                       @Doc("When she is in another dimension than you (then there is no distance).")
                       Optional<String> dimension) {}

    /** 存档里记着的一只:没加载的区块里的女仆最后在哪,或一块墓碑在哪。 */
    @Doc("Where a maid in an unloaded chunk was last, or where a tombstone stands.")
    public record LastSeen(String name, BlockPos pos, String dimension) {}

    /** 她名下的。 */
    @Doc("The maids you keep.")
    public record Household(@Doc("Loaded now, nearest first.") List<Maid> here,
                            @Doc("In unloaded chunks: where each was last.") List<LastSeen> away,
                            @Doc("Where each tombstone stands.") List<LastSeen> tombstones) {}

    @Fn("The maids you keep: the ones here, the ones in unloaded chunks, and tombstones.")
    @Example("tlm.maid.list()")
    @Example("for _, m in ipairs(tlm.maid.list().here) do print(m.id, m.task, m.distance) end")
    @Note("Read-only. A wild maid is tamed with a cake: `minecraft.use.entity(\"maid@3f9a1c\", {item = \"minecraft:cake\"})`, with her "
            + "id from `minecraft.scan.entities`.")
    @Note("Your power points and how many maids TLM counts as yours are in your body state every turn.")
    @SeeAlso({"tlm.maid.info", "minecraft.scan.entities"})
    public static Household list(ServerCall call) {
        NumenPlayer her = call.her();
        List<Maid> here = new ArrayList<>();
        Maids.loaded(her).forEach(maid -> here.add(Maids.row(maid, her)));
        return new Household(here, Maids.away(her), Maids.tombstones(her));
    }

    /** 一个工作模式。 */
    @Doc("A work mode in a maid's task list.")
    public record WorkMode(String task,
                           @Doc("true for the one she works as now.") Optional<Boolean> current,
                           @Doc("Whether TLM lets her switch to it now.") boolean canSwitch,
                           @Doc("What it waits for, true = met.") Optional<Map<String, Boolean>> toEnable,
                           @Doc("What the work uses (has_bow, has_arrow for ranged_attack), true = she has it.")
                           Optional<Map<String, Boolean>> worksWith) {}

    /** 日程点。 */
    @Doc("Where she works, idles and sleeps on her schedule.")
    public record SchedulePoints(BlockPos work, BlockPos idle, BlockPos sleep, String dimension) {}

    /** 她身上的一样东西:穿着的、拿着的、带着的。 */
    @Doc("An item on her and how many.")
    public record Held(String item, int count) {}

    /** 身上的一个药水效果。 */
    @Doc("A potion effect on her.")
    public record Effect(String effect, int amplifier, @Doc("Ticks left; -1 for endless.") int ticks) {}

    /** 一只女仆的详情。 */
    @Doc("One maid in full.")
    public record Detail(@Doc("Her, as tlm.maid.list lists her.") Maid maid,
                         boolean pickup,
                         boolean ride,
                         int favorability,
                         int favorabilityToNextLevel,
                         String backpack,
                         @Doc("With home mode on.") Optional<BlockPos> homeCenter,
                         @Doc("With home mode on.") Optional<Double> homeRadius,
                         @Doc("When they are set.") Optional<SchedulePoints> schedulePoints,
                         Preferences preferences,
                         @Doc("By slot: mainhand, offhand, head, chest, legs, feet; an empty slot is left out.")
                         Map<String, Held> equipment,
                         @Doc("What her own slots and her backpack hold, one row per kind of item.") List<Held> items,
                         @Doc("The baubles she wears.") List<Held> baubles,
                         List<Effect> effects,
                         @Doc("The experience she carries.") int experience,
                         @Doc("Whether she cannot be hurt.") boolean invulnerable,
                         @Doc("What her schedule has her doing now: minecraft:work, minecraft:idle or "
                                 + "minecraft:rest.") String activity,
                         boolean sleeping,
                         @Doc("What she is fighting now.") Optional<EntityInfo> target,
                         @Doc("Every work mode in her task list.") List<WorkMode> tasks) {}

    /** 哪一只。 */
    public record Which(@Doc("The maid: her Maid or Entity, or her id as tlm.maid.list or minecraft.scan.entities "
            + "lists it.") EntityRef maid) {}

    @Fn("One maid in full: settings, gear, what she carries, effects, what she is doing, and every work mode with "
            + "what it needs.")
    @Example("tlm.maid.info(\"maid@3f9a1c\")")
    @Example("for _, t in ipairs(tlm.maid.info(\"maid@3f9a1c\").tasks) do print(t.task, t.can_switch) end")
    @Note("Read-only, from any distance, any maid (someone else's too).")
    @Note("Every work mode: can_switch says whether TLM lets her switch to it now; to_enable lists what it waits for "
            + "(true = met); works_with lists what the work uses (e.g. has_bow, has_arrow for ranged_attack), true = "
            + "she has it.")
    @Note("Whom she attacks is tlm.maid.targets.")
    @SeeAlso({"tlm.maid.task", "tlm.maid.config", "tlm.maid.targets"})
    public static Detail info(ServerCall call, Which args) {
        return Maids.detail(maid(call, args.maid()), call.her());
    }

    /** 切到哪个工作模式、哪一只。 */
    public record Task(@Doc("The work mode: a task id as tlm.maid.info lists it, e.g. touhou_little_maid:farm.")
                       ResourceLocation task,
                       @Doc("The maid.") @Omitted("your maid within reach (the nearest one)") Optional<EntityRef> maid) {}

    /** 切完读回的工作模式。 */
    @Doc("Her work mode, read back.")
    public record Switched(@Doc("Her id.") String maid, String task) {}

    @Fn("Switch one of your maids to another work mode, like a click in her task list.")
    @Example("tlm.maid.task(\"touhou_little_maid:farm\", {maid = \"maid@3f9a1c\"})")
    @Example("tlm.maid.task(\"touhou_little_maid:idle\")")
    @Note("It does not travel: stand within about 7 blocks of her, the distance at which her GUI stays open. Farther "
            + "away it fails with out_of_reach, and its hint is the minecraft.move.walk call to copy.")
    @Note("TLM decides: only the owner may switch, and a mode may wait for something first (see can_switch in "
            + "tlm.maid.info). It reads her task back; when TLM did not take it, it fails and says what TLM's rules show.")
    @Note("It is using your maid, so your owner's rules may ask them first; the call waits for the answer.")
    @SeeAlso({"tlm.maid.info", "tlm.maid.config"})
    public static Pending<Switched> task(ServerCall call, Task args) {
        ResourceLocation task = args.task();
        if (!Maids.taskExists(task)) {
            List<String> same = Maids.tasksNamed(task.getPath());
            Optional<String> named = args.maid().map(EntityRef::label);
            throw new ApiError(ErrorKind.NOT_FOUND, "TLM has no work mode " + task
                    + (same.isEmpty() ? "" : " — did you mean " + String.join(" or ", same) + "?")
                    + (args.maid().isEmpty() ? "; tlm.maid.info lists a maid's work modes" : ""),
                    named.map(id -> Call.of("tlm.maid.info", id)).orElse(null));
        }
        Entity maid = args.maid().isPresent() ? maid(call, args.maid().get()) : yoursWithinReach(call);
        return call.use(maid, GUI, still -> {
            NumenPlayer her = call.her();
            String was = Maids.task(still);
            Maids.switchTask(her, still, task);
            String now = Maids.task(still);
            if (!now.equals(task.toString())) {
                throw refused(her, still, task, Maids.label(still) + " still works as " + now
                        + "; TLM did not switch her to " + task + ".", new Switched(EntityLabel.of(still), now));
            }
            return new Switched(EntityLabel.of(still), now);
        });
    }

    /** 改哪几样。 */
    public record Config(@Doc("The maid.") EntityRef maid,
                         @Doc("Home mode: true keeps her working and resting around her home or schedule points; false "
                                 + "has her follow you.") @Omitted("leave it as it is") Optional<Boolean> home,
                         @Doc("Whether she picks up items, experience and power points around her.")
                         @Omitted("leave it as it is") Optional<Boolean> pickup,
                         @Doc("Whether she may ride things; false also gets her off what she rides now (not off a chair "
                                 + "or a flying broom).") @Omitted("leave it as it is") Optional<Boolean> ride,
                         @Doc("When she works: day works by day and sleeps at night, night the other way round, all "
                                 + "works round the clock.") @Omitted("leave it as it is") Optional<Schedule> schedule,
                         @Doc("Whether her backpack shows on her back.") @Omitted("leave it as it is")
                         Optional<Boolean> showBackpack,
                         @Doc("Whether the item she carries on her back shows.") @Omitted("leave it as it is")
                         Optional<Boolean> showBackItem,
                         @Doc("Whether her chat bubbles show.") @Omitted("leave it as it is")
                         Optional<Boolean> chatBubble,
                         @Doc("How often she speaks, 0 to 1.") @Omitted("leave it as it is")
                         Optional<Double> soundFrequency,
                         @Doc("What she picks up while picking up is on: item, xp or all.")
                         @Omitted("leave it as it is") Optional<PickupKind> pickupKind,
                         @Doc("Whether she opens doors.") @Omitted("leave it as it is") Optional<Boolean> openDoor,
                         @Doc("Whether she opens fence gates.") @Omitted("leave it as it is")
                         Optional<Boolean> openFenceGate,
                         @Doc("Whether she climbs on her own.") @Omitted("leave it as it is")
                         Optional<Boolean> activeClimbing) {

        /** 落在「女仆配置」那一页上的有没有。 */
        boolean touchesPreferences() {
            return showBackpack.isPresent() || showBackItem.isPresent() || chatBubble.isPresent()
                    || soundFrequency.isPresent() || pickupKind.isPresent() || openDoor.isPresent()
                    || openFenceGate.isPresent() || activeClimbing.isPresent();
        }
    }

    /** 改完读回的设置。 */
    @Doc("Her settings, read back.")
    public record Configured(@Doc("Her id.") String maid, boolean home, boolean pickup, boolean ride,
                             Schedule schedule, Preferences preferences) {}

    @Fn("Change one of your maids' settings: home mode, picking up, riding, schedule, and the maid config page.")
    @Example("tlm.maid.config(\"maid@3f9a1c\", {schedule = \"night\"})")
    @Example("tlm.maid.config(\"maid@3f9a1c\", {home = true, pickup = false})")
    @Example("tlm.maid.config(\"maid@3f9a1c\", {pickup_kind = \"xp\", open_door = false, sound_frequency = 0.3})")
    @Note("Give only what you change; the rest stays. The same reach, owner rule and asking as tlm.maid.task.")
    @Note("TLM keeps home mode off when her schedule points are in another dimension or more than 32 blocks from her; "
            + "turning it on with no points set makes where she stands her home.")
    @Note("It reads every setting back; when one of yours did not take, it fails and says which.")
    @SeeAlso({"tlm.maid.info", "tlm.maid.task"})
    public static Pending<Configured> config(ServerCall call, Config args) {
        if (args.home().isEmpty() && args.pickup().isEmpty() && args.ride().isEmpty() && args.schedule().isEmpty()
                && !args.touchesPreferences()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "nothing to change: give one or more settings; tlm.maid.info "
                    + "shows her settings now",
                    Call.of("tlm.maid.info", args.maid().label()));
        }
        args.soundFrequency().filter(v -> v < 0 || v > 1).ifPresent(v -> {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "sound_frequency is 0 to 1, not " + v, null);
        });
        return call.use(maid(call, args.maid()), GUI, still -> {
            NumenPlayer her = call.her();
            Maids.Settings was = Maids.settings(still);
            Maids.configure(her, still, new Maids.Settings(args.home().orElse(was.home()),
                    args.pickup().orElse(was.pickup()), args.ride().orElse(was.ride()),
                    args.schedule().orElse(was.schedule())));
            if (args.touchesPreferences()) {
                Preferences p = Maids.preferences(still);
                Maids.configure(her, still, new Preferences(args.showBackpack().orElse(p.showBackpack()),
                        args.showBackItem().orElse(p.showBackItem()), args.chatBubble().orElse(p.chatBubble()),
                        args.soundFrequency().orElse(p.soundFrequency()), args.pickupKind().orElse(p.pickupKind()),
                        args.openDoor().orElse(p.openDoor()), args.openFenceGate().orElse(p.openFenceGate()),
                        args.activeClimbing().orElse(p.activeClimbing())));
            }
            Maids.Settings now = Maids.settings(still);
            Preferences nowPreferences = Maids.preferences(still);
            Configured read = new Configured(EntityLabel.of(still), now.home(), now.pickup(), now.ride(), now.schedule(),
                    nowPreferences);
            List<String> refused = new ArrayList<>();
            args.home().filter(v -> v != now.home()).ifPresent(v -> refused.add("home = " + v));
            args.pickup().filter(v -> v != now.pickup()).ifPresent(v -> refused.add("pickup = " + v));
            args.ride().filter(v -> v != now.ride()).ifPresent(v -> refused.add("ride = " + v));
            args.schedule().filter(v -> v != now.schedule()).ifPresent(v -> refused.add("schedule = \""
                    + v.name().toLowerCase(java.util.Locale.ROOT) + "\""));
            args.showBackpack().filter(v -> v != nowPreferences.showBackpack())
                    .ifPresent(v -> refused.add("show_backpack = " + v));
            args.showBackItem().filter(v -> v != nowPreferences.showBackItem())
                    .ifPresent(v -> refused.add("show_back_item = " + v));
            args.chatBubble().filter(v -> v != nowPreferences.chatBubble())
                    .ifPresent(v -> refused.add("chat_bubble = " + v));
            args.soundFrequency().filter(v -> Math.abs(v - nowPreferences.soundFrequency()) > 0.005)
                    .ifPresent(v -> refused.add("sound_frequency = " + v));
            args.pickupKind().filter(v -> v != nowPreferences.pickupKind()).ifPresent(v -> refused
                    .add("pickup_kind = \"" + v.name().toLowerCase(java.util.Locale.ROOT) + "\""));
            args.openDoor().filter(v -> v != nowPreferences.openDoor()).ifPresent(v -> refused.add("open_door = " + v));
            args.openFenceGate().filter(v -> v != nowPreferences.openFenceGate())
                    .ifPresent(v -> refused.add("open_fence_gate = " + v));
            args.activeClimbing().filter(v -> v != nowPreferences.activeClimbing())
                    .ifPresent(v -> refused.add("active_climbing = " + v));
            if (!refused.isEmpty()) {
                throw refused(her, still, null, Maids.label(still) + " did not take " + String.join(", ", refused)
                        + ".", read);
            }
            return read;
        });
    }

    /** 改攻击名单的哪几条。 */
    public record SetTargets(@Doc("The maid.") EntityRef maid,
                             @Doc("Entity type id to stance, e.g. {[\"minecraft:cow\"] = \"hostile\"}: friendly is "
                                     + "never attacked, neutral only when it hurt you or her or was hurt by either of "
                                     + "you, hostile on sight.") @Omitted("change nothing")
                             Optional<Map<String, Stance>> set,
                             @Doc("Entity type ids to take off her list, so TLM's default for them applies again.")
                             @Omitted("remove nothing") Optional<List<String>> remove) {}

    /** 她的攻击名单。 */
    @Doc("Her attack list.")
    public record Aims(@Doc("Her id.") String maid,
                       @Doc("Entity type id to stance; only the types she was given a stance for.")
                       Map<String, Stance> stances) {}

    @Fn("Whom one maid attacks: her attack list.")
    @Example("tlm.maid.targets(\"maid@3f9a1c\")")
    @Note("Read-only, from any distance, any maid.")
    @Note("The list holds only the types given a stance; any other type is judged by TLM's default: monsters hostile, "
            + "tamed animals and villagers friendly, the rest neutral.")
    @SeeAlso({"tlm.maid.set_targets", "tlm.maid.info"})
    public static Aims targets(ServerCall call, Which args) {
        return Maids.aims(maid(call, args.maid()));
    }

    @Fn("Change whom one maid attacks, like the attack mode's config page.")
    @Example("tlm.maid.set_targets(\"maid@3f9a1c\", {set = {[\"minecraft:creeper\"] = \"friendly\"}})")
    @Example("tlm.maid.set_targets(\"maid@3f9a1c\", {remove = {\"minecraft:creeper\"}})")
    @Note("Give only what you change. The same reach, owner rule and asking as tlm.maid.task.")
    @Note("It reads the list back; when it did not take, it fails and says so.")
    @SeeAlso({"tlm.maid.targets", "tlm.maid.task"})
    public static Pending<Aims> setTargets(ServerCall call, SetTargets args) {
        if (args.set().isEmpty() && args.remove().isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "nothing to change: give set or remove; tlm.maid.targets shows "
                    + "her list now", Call.of("tlm.maid.targets", args.maid().label()));
        }
        Entity maid = maid(call, args.maid());
        Map<String, Stance> wanted = new java.util.TreeMap<>(Maids.aims(maid).stances());
        args.set().ifPresent(set -> set.forEach((type, stance) -> wanted.put(Maids.entityType(type), stance)));
        args.remove().ifPresent(types -> types.forEach(type -> wanted.remove(Maids.entityType(type))));
        return call.use(maid, GUI, still -> {
            Maids.aim(call.her(), still, wanted);
            Aims read = Maids.aims(still);
            if (!read.stances().equals(wanted)) {
                throw refused(call.her(), still, null, Maids.label(still) + " did not take the attack list.", read);
            }
            return read;
        });
    }

    /** 改名,和拿名牌右键她、在名牌界面里点完成一样。 */
    public record Name(@Doc("The maid.") EntityRef maid,
                       @Doc("The new name, up to 32 characters.") String name,
                       @Doc("Whether the name shows above her all the time.") @Omitted("it does not")
                       Optional<Boolean> alwaysShow) {}

    /** 改完读回的名字。 */
    @Doc("Her name, read back.")
    public record Named(@Doc("Her id.") String maid, String name, boolean alwaysShow,
                        @Doc("Whether TLM took the name tag from your hand.") boolean nameTagUsed) {}

    @Fn("Name one of your maids, like using a name tag on her.")
    @Example("tlm.maid.name(\"maid@3f9a1c\", \"Reimu\")")
    @Example("tlm.maid.name(\"maid@3f9a1c\", \"Reimu\", {always_show = true})")
    @Note("TLM names a maid only while you hold a name tag in your main hand, and uses the tag up: "
            + "`minecraft.gear.hold(\"minecraft:name_tag\")` first. The tag's own text does not matter.")
    @Note("The same reach, owner rule and asking as tlm.maid.task. It reads her name back.")
    @SeeAlso({"tlm.maid.info", "minecraft.gear.hold"})
    public static Pending<Named> name(ServerCall call, Name args) {
        if (args.name().isBlank() || args.name().length() > Maids.NAME_MAX) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a maid's name is 1 to " + Maids.NAME_MAX + " characters", null);
        }
        boolean show = args.alwaysShow().orElse(false);
        return call.use(maid(call, args.maid()), GUI, still -> {
            NumenPlayer her = call.her();
            int tags = Maids.nameTags(her);
            Maids.name(her, still, args.name(), show);
            boolean tagged = tags > 0;
            Named read = new Named(EntityLabel.of(still), still.hasCustomName() ? still.getCustomName().getString() : "",
                    still.isCustomNameVisible(), Maids.nameTags(her) < tags);
            if (!args.name().equals(read.name()) || show != read.alwaysShow()) {
                String said = Maids.label(still) + " was not named.";
                if (Maids.ownedBy(still, her) && !tagged) {
                    throw new ApiError(ErrorKind.FAILED, said + " TLM names a maid only with a name tag in your main "
                            + "hand.", Call.of("minecraft.gear.hold", "minecraft:name_tag"), read);
                }
                throw refused(her, still, null, said, read);
            }
            return read;
        });
    }

    /** 换哪一只的模型。 */
    public record SetModel(@Doc("The maid.") EntityRef maid,
                           @Doc("A model id as tlm.skin.list lists it.") String model) {}

    /** 她穿的模型。 */
    @Doc("The model a maid wears.")
    public record Wearing(@Doc("Her id.") String maid, String model) {}

    @Fn("Which model one maid wears.")
    @Example("tlm.maid.model(\"maid@3f9a1c\")")
    @Note("Read-only, from any distance, any maid.")
    @SeeAlso({"tlm.maid.set_model", "tlm.skin.list"})
    public static Wearing model(ServerCall call, Which args) {
        Entity maid = maid(call, args.maid());
        return new Wearing(EntityLabel.of(maid), Maids.model(maid));
    }

    @Fn("Change the model one maid wears, like picking a model in her model screen.")
    @Example("tlm.maid.set_model(\"maid@3f9a1c\", \"touhou_little_maid:hakurei_reimu\")")
    @Note("The same reach, owner rule and asking as tlm.maid.task. The ids are the ones tlm.skin.list shows.")
    @Note("TLM's server setting may forbid changing a maid's model; it reads the model back and fails when it did "
            + "not change.")
    @SeeAlso({"tlm.maid.model", "tlm.skin.list"})
    public static Pending<Wearing> setModel(ServerCall call, SetModel args) {
        Entity maid = maid(call, args.maid());
        String model = args.model();
        if (!Maids.hasModel(model)) {
            throw new ApiError(ErrorKind.NOT_FOUND, "this server has no maid model " + model
                    + "; tlm.skin.list shows the installed ones", Call.of("tlm.skin.list", Map.of("search", model)));
        }
        return call.use(maid, GUI, still -> {
            Maids.model(call.her(), still, model);
            Wearing read = new Wearing(EntityLabel.of(still), Maids.model(still));
            if (!model.equals(read.model())) {
                throw refused(call.her(), still, null, Maids.label(still) + " still wears " + read.model()
                        + "; TLM did not change it to " + model + " (its server setting may forbid changing a "
                        + "maid's model).", read);
            }
            return read;
        });
    }

    /** 递给谁、递什么、递几件、放到她身上哪一处。 */
    public record Give(@Doc("The maid.") EntityRef maid,
                       @Doc("The item, minecraft:wheat_seeds.") StackRef item,
                       @Doc("How many.") @Omitted("all you carry of it") @Positional Optional<Integer> count,
                       @Doc("Where on her: backpack (her own slots and her backpack), mainhand, offhand, head, chest, "
                               + "legs, feet, or bauble.") @Omitted("backpack") Optional<Place> slot) {}

    @Fn("Hand items from your inventory to one of your maids: into her backpack, or onto a slot she holds or wears.")
    @Example("tlm.maid.give(\"maid@3f9a1c\", \"minecraft:wheat_seeds\", 16)")
    @Example("tlm.maid.give(\"maid@3f9a1c\", \"minecraft:bow\", 1, {slot = \"mainhand\"})")
    @Note("The same reach, owner rule and asking as tlm.maid.task. A sleeping maid takes nothing.")
    @Note("TLM decides what a slot takes: an armour slot only what fits it, the bauble slots only baubles. A hand or "
            + "armour slot that holds something else takes nothing; take that out first.")
    @Note("Returns how many went over; fewer than asked when she ran out of room. None at all fails and says why.")
    @Note("Naming only the item hands over any stack of it. To hand over one kind of it only (the enchanted bow, not "
            + "the plain one), pass that kind's entry of minecraft.inv.items() as item.")
    @SeeAlso({"tlm.maid.take", "tlm.maid.info"})
    public static Pending<Integer> give(ServerCall call, Give args) {
        Stacks.Which which = args.item().in(call.her().getInventory(), call.her().registryAccess());
        Item item = which.item();
        int count = atLeastOne(args.count());
        Place place = args.slot().orElse(Place.BACKPACK);
        mustCarry(call.her(), which);
        return call.use(maid(call, args.maid()), GUI, still -> {
            NumenPlayer her = call.her();
            mustCarry(her, which);
            int had = carried(her, item);
            inGui(her, still, place == Place.BAUBLE, () -> Maids.give(her, still, which, count, place));
            return had - carried(her, item);
        });
    }

    /** 从谁身上拿、拿什么、拿几件。 */
    public record Take(@Doc("The maid.") EntityRef maid,
                       @Doc("The item, minecraft:wheat.") Item item,
                       @Doc("How many.") @Omitted("all she has of it") @Positional Optional<Integer> count) {}

    @Fn("Take items from one of your maids into your inventory.")
    @Example("tlm.maid.take(\"maid@3f9a1c\", \"minecraft:wheat\")")
    @Example("tlm.maid.take(\"maid@3f9a1c\", \"minecraft:arrow\", 16)")
    @Note("It takes from her backpack first, then what she holds, what she wears, and her baubles. What she has is in "
            + "tlm.maid.info: equipment, items, baubles.")
    @Note("The same reach, owner rule and asking as tlm.maid.task. A sleeping maid gives nothing.")
    @Note("Returns how many came over; fewer than asked when she had no more or your inventory filled up. None at all "
            + "fails: not_found when she has none.")
    @SeeAlso({"tlm.maid.give", "tlm.maid.info"})
    public static Pending<Integer> take(ServerCall call, Take args) {
        Item item = args.item();
        int count = atLeastOne(args.count());
        Entity maid = maid(call, args.maid());
        mustHave(maid, item);
        return call.use(maid, GUI, still -> {
            NumenPlayer her = call.her();
            mustHave(still, item);
            int had = carried(her, item);
            inGui(her, still, false, () -> Maids.take(her, still, item, count, false));
            int got = carried(her, item) - had;
            if (got < count && Maids.wearsBauble(still, item)) {
                int more = count - got;
                inGui(her, still, true, () -> Maids.take(her, still, item, more, true));
                got = carried(her, item) - had;
            }
            if (got == 0) {
                boolean room = her.getInventory().getFreeSlot() >= 0
                        || her.getInventory().getSlotWithRemainingSpace(new ItemStack(item)) >= 0;
                throw new ApiError(ErrorKind.FAILED, room
                        ? "TLM does not let the " + id(item) + " off " + Maids.label(still) + " (armour cursed with "
                                + "binding stays on)."
                        : "your inventory has no room for " + id(item) + ".", null);
            }
            return got;
        });
    }

    /**
     * 打开她界面的一页、在里面做 {@code work}、合上:界面只在这一次调用里。车万女仆没开这一页就失败,说它的规矩此刻怎么说。
     */
    private static void inGui(NumenPlayer her, Entity maid, boolean baubles, Runnable work) {
        if (!Maids.open(her, maid, baubles)) {
            String said = "TLM did not open the GUI of " + Maids.label(maid) + ".";
            throw Maids.asleep(maid)
                    ? new ApiError(ErrorKind.FAILED, said + " She is asleep; a sleeping maid's GUI does not open.", null)
                    : refused(her, maid, null, said, null);
        }
        try {
            work.run();
        } finally {
            her.closeContainer();
        }
    }

    /** 件数:不写是有多少算多少,写了得是 1 以上。 */
    private static int atLeastOne(Optional<Integer> count) {
        if (count.isPresent() && count.get() < 1) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "count is " + count.get() + "; give 1 or more, or leave it out "
                    + "for all of it", null);
        }
        return count.orElse(Integer.MAX_VALUE);
    }

    /** 她背包那 36 格里有几件 {@code item}:女仆界面里搬东西只经这些格,递过去、拿回来几件都按它前后的差算。 */
    private static int carried(NumenPlayer her, Item item) {
        int n = 0;
        for (ItemStack stack : her.getInventory().items) {
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static void mustCarry(NumenPlayer her, Stacks.Which which) {
        if (Stacks.pick(her.getInventory(), which).isEmpty()) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "you carry no " + id(which.item()), null);
        }
    }

    private static void mustHave(Entity maid, Item item) {
        if (Maids.carries(maid, item) == 0) {
            throw new ApiError(ErrorKind.NOT_FOUND, Maids.label(maid) + " has no " + id(item) + " on her; "
                    + "tlm.maid.info lists what she has", Call.of("tlm.maid.info", EntityLabel.of(maid)));
        }
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** 点名的那只女仆;不在或不是女仆就失败。 */
    private static Entity maid(ServerCall call, EntityRef ref) {
        Entity entity = call.entity(ref);
        if (!Maids.is(entity)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, ref + " is "
                    + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + ", not a maid", Call.of("tlm.maid.list"));
        }
        return entity;
    }

    /** 她自己的、够得着的女仆里最近的那一只;一只都没有就失败。 */
    private static Entity yoursWithinReach(ServerCall call) {
        NumenPlayer her = call.her();
        Entity nearest = null;
        boolean any = false;
        for (Entity maid : Maids.loaded(her)) {
            if (!Maids.ownedBy(maid, her)) {
                continue;
            }
            any = true;
            if (Maids.inReach(her, maid) && (nearest == null || her.distanceToSqr(maid) < her.distanceToSqr(nearest))) {
                nearest = maid;
            }
        }
        if (nearest == null) {
            throw new ApiError(any ? ErrorKind.OUT_OF_REACH : ErrorKind.NOT_FOUND, "none of your maids is within reach "
                    + "— name one with {maid = <id>}, or walk to her first", Call.of("tlm.maid.list"));
        }
        return nearest;
    }

    /**
     * 车万女仆没照做时的那条失败:话是 {@code said} 接上它自己的规矩此刻怎么说——不是主人(拒绝,野生的给出驯服那一下);这个工作模式
     * 还没开,开它要什么。只读、只说,判断仍在车万女仆的包里。
     *
     * @param task 切工作模式时是要切的那个;别的动作为 null
     * @param read 调完读回的;没有为 null
     */
    private static ApiError refused(NumenPlayer her, Entity maid, ResourceLocation task, String said, Record read) {
        if (!Maids.ownedBy(maid, her)) {
            String owner = Maids.owner(maid);
            if (owner == null) {
                return new ApiError(ErrorKind.DENIED, said + " She is wild: tame her first with a cake.",
                        Call.of("minecraft.use.entity", EntityLabel.of(maid), Map.of("item", "minecraft:cake")), read);
            }
            return new ApiError(ErrorKind.DENIED, said + " She is not yours: TLM lets only her owner (" + owner
                    + ") do this.", null, read);
        }
        if (task != null) {
            Map<String, Boolean> missing = Maids.notEnabled(maid, task);
            if (missing != null) {
                return new ApiError(ErrorKind.FAILED, said + " TLM has not enabled " + task + " for her"
                        + (missing.isEmpty() ? " (another mod holds it back)." : "; it waits for: " + missing + "."),
                        null, read);
            }
        }
        return new ApiError(ErrorKind.FAILED, said, null, read);
    }
}
