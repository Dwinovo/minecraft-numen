package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.permission.Edit;
import com.dwinovo.numen.permission.Settings;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * {@code numen.permission}:她看自己的权限设置,和改它。
 *
 * <p>她只能往严了调:把一个档位调严、从"不用问"的名单里删一项,当场生效、回执说清,也直接告诉主人;放宽一律经主人——把一个档位
 * 调松、往名单里加一项都发一张征询卡,主人答应才写,拒绝了不写。否则就是她自己给自己发许可。严与松的判据在纯逻辑核心
 * ({@link Edit#loosens})。
 *
 * <p>判与问仍归权限层:这里不裁决任何动作,只改设置这份数据。
 */
public final class PermissionApi {

    private PermissionApi() {}

    /** 一种动作此刻的档位与名单。 */
    public record Row(
            @Doc("The verb: break, place, use_block, attack, use_entity, drop, use_item or command.") String verb,
            @Doc("Your owner's tier for it: allow, ask or deny. It decides what nobody else has a say in.") String tier,
            @Doc("Names your owner never wants to be asked about for this verb.") List<String> neverAsk) {}

    /** 她此刻的设置。 */
    public record Current(@Doc("One row per verb, eight in all.") List<Row> verbs) {}

    @Fn("What your owner lets you do to the world: a tier per kind of act, and the names he never wants to be asked about.")
    @Example("numen.permission.settings()")
    @Note("Read-only. Before you act, everyone who knows the thing is asked: anyone may say no (final), then a name your "
            + "owner never wants to be asked about goes ahead, then anyone may say ask, then anyone may say yes; if nobody "
            + "has a say, the verb's tier decides.")
    @Note("Names are what the verb acts on: a block id for break and use_block, an item id for place, drop and use_item, "
            + "this one entity (uuid) for attack and use_entity when it is a player or somebody's, otherwise its kind "
            + "(entity type id), a command's root name for command.")
    @Note("Taking things out of a container is not a verb of its own: it is decided by the use_block that opens "
            + "the container.")
    @SeeAlso({"numen.permission.set", "numen.permission.add", "numen.permission.remove"})
    public static Current settings(ServerCall call) {
        return current(Permission.settingsOf(call.her()));
    }

    static Current current(Settings settings) {
        List<Row> rows = new ArrayList<>();
        for (Verb verb : Verb.values()) {
            rows.add(new Row(verb.word(), settings.tier(verb).word(), settings.names(verb)));
        }
        return new Current(rows);
    }

    /** 改了什么。 */
    public record Changed(@Doc("What it is now, as numen.permission.settings() lists it: the verb and its tier, or the "
            + "verb and the name.") String now,
                          @Doc("false when it already was so.") boolean changed,
                          @Doc("true when your owner was in the game and has been told about a tightening you made "
                                  + "yourself; otherwise he sees it in his permission settings.") boolean told) {}

    @Fn("Set the tier of one verb: allow, ask or deny.")
    @Example("numen.permission.set(\"break\", \"deny\")")
    @Note("Tightening (toward deny) takes effect at once and does not ask; your owner is told. Loosening is your owner's "
            + "call: this puts a card in front of him and waits, and the tier changes only if he allows.")
    @SeeAlso({"numen.permission.settings", "numen.permission.add"})
    public static Pending<Changed> set(ServerCall call, SetTier args) {
        Edit edit = new Edit.SetTier(verb(args.verb()), tier(args.tier()));
        return change(call, edit, "numen permission set " + name(call.her()) + " " + args.verb() + " " + args.tier());
    }

    /** 定一个档位。 */
    public record SetTier(@Doc("The verb: break, place, use_block, attack, use_entity, drop, use_item or command.")
                          String verb,
                          @Doc("allow, ask or deny.") String tier) {}

    @Fn("Ask your owner to put a name on a verb's never-ask list: acts of that verb on that name no longer need asking.")
    @Example("numen.permission.add(\"break\", \"minecraft:wheat\")")
    @Note("Loosening is your owner's call: this puts a card in front of him and waits. Written only if he allows. "
            + "Anyone who knows the thing may still say no, and that always wins over the list.")
    @SeeAlso({"numen.permission.settings", "numen.permission.remove"})
    public static Pending<Changed> add(ServerCall call, Entry args) {
        Verb verb = verb(args.verb());
        return change(call, new Edit.Add(verb, args.name()),
                "numen permission list add " + name(call.her()) + " " + verb.word() + " " + args.name());
    }

    @Fn("Take a name off a verb's never-ask list: acts of that verb on that name are asked about again, from now on.")
    @Example("numen.permission.remove(\"break\", \"minecraft:chest\")")
    @Note("Takes effect at once and does not ask: tightening needs no consent. Your owner is told.")
    @SeeAlso({"numen.permission.settings", "numen.permission.add"})
    public static Pending<Changed> remove(ServerCall call, Entry args) {
        return change(call, new Edit.Remove(verb(args.verb()), args.name()), "");
    }

    /** 名单里的一项。 */
    public record Entry(@Doc("The verb: break, place, use_block, attack, use_entity, drop, use_item or command.")
                        String verb,
                        @Doc("The name, as numen.permission.settings() lists it.") String name) {}

    // ==================== 内部 ====================

    /** 往严了调的当场写、告诉主人;放宽的发卡,主人答应了才写。 */
    private static Pending<Changed> change(ServerCall call, Edit edit, String command) {
        NumenPlayer her = call.her();
        PermissionStore store = requiredStore(her);
        Settings now = store.settingsOf(her.getUUID());
        if (edit.loosens(now)) {
            return call.consent(ConsentItem.settingsEdit(command)).then(allowed -> write(her, store, edit, false));
        }
        return Pending.of(write(her, store, edit, true));
    }

    private static Changed write(NumenPlayer her, PermissionStore store, Edit edit, boolean tightening) {
        boolean changed = !edit.applyTo(store.settingsOf(her.getUUID())).equals(store.settingsOf(her.getUUID()));
        store.edit(her.getUUID(), edit);
        ServerPlayer owner = her.resolveOwnerPlayer();
        boolean told = tightening && changed && owner != null;
        if (told) {
            owner.sendSystemMessage(Component.translatable(ModLanguageData.Keys.PERMISSION_TIGHTENED, her.getName(),
                    edit.verb().word(), describe(edit)));
        }
        return new Changed(describeNow(edit), changed, told);
    }

    private static String describe(Edit edit) {
        return switch (edit) {
            case Edit.SetTier t -> t.tier().word();
            case Edit.Add a -> "+ " + a.name();
            case Edit.Remove r -> "- " + r.name();
        };
    }

    private static String describeNow(Edit edit) {
        return switch (edit) {
            case Edit.SetTier t -> t.verb().word() + " " + t.tier().word();
            case Edit.Add a -> a.verb().word() + " never asks about " + a.name();
            case Edit.Remove r -> r.verb().word() + " asks about " + r.name() + " again";
        };
    }

    private static String name(NumenPlayer her) {
        return her.getName().getString();
    }

    private static Verb verb(String word) {
        Verb verb = Verb.byWord(word);
        if (verb == null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "'" + word + "' is not a verb; the verbs are "
                    + Arrays.stream(Verb.values()).map(Verb::word).toList(), Call.of("numen.permission.settings"));
        }
        return verb;
    }

    private static Tier tier(String word) {
        Tier tier = Tier.byWord(word);
        if (tier == null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "'" + word + "' is not a tier; the tiers are allow, ask, deny",
                    Call.of("numen.permission.settings"));
        }
        return tier;
    }

    private static PermissionStore requiredStore(NumenPlayer her) {
        UUID owner = her.getOwnerUuid();
        if (owner == null) {
            throw new ApiError(ErrorKind.FAILED, "you have no owner whose settings these could be", null);
        }
        return PermissionStore.of(her.getServer(), owner);
    }
}
