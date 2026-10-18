package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.agent.script.ApiError;
import com.dwinovo.numen.api.agent.script.ErrorKind;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code numen.permission}:她看自己受着哪些规则,和改主人的规则。
 *
 * <p>只能收紧:{@code deny}、{@code ask} 她自己加,当场写进主人那一层、回执说清,也直接告诉主人;放宽一律经主人——
 * {@code allow} 与删掉任何一行都发一张征询卡,主人答应才写,拒绝了不写。否则就是她自己给自己发许可。她加的行在规则列表里记着来源
 * 与她说的理由,主人删起来和删自己的一样。
 *
 * <p>判与问仍归权限层:这里不裁决任何动作,只改规则这份数据。
 */
public final class PermissionApi {

    /** 列表里一行的规则与后面的注(谁写的、命中几次)之间的分隔;她把整行抄回来,注也认得出来、不算规则。 */
    private static final String NOTE_MARK = "  -- ";

    private PermissionApi() {}

    /** 她此刻受着的规则,一行一条,写法和 {@code add}/{@code remove} 认的一样。 */
    public record Rules(
            @Doc("Rules the server owner set; nobody can lift them, not even your owner.") List<String> hard,
            @Doc("Your owner's rules, checked first: \"deny break(...)\" lines etc. Each notes who wrote it (you may "
                    + "have) and how often it fired.") List<String> yours,
            @Doc("The rules that ship with the mod, checked after your owner's.") List<String> factory) {}

    @Fn("The rules that decide what you may do to the world, in the order they are checked.")
    @Example("numen.permission.rules()")
    @Note("Read-only. A deny anywhere always wins; then allow before ask, your owner's layer before the factory's; "
            + "what no row says is asked of your owner.")
    @Note("Rows are written verb(condition) @Name: verbs break, place, attack, use_block, use_entity, take, drop, "
            + "command; conditions combine groups (self, owner, other, structure, nature, unknown), properties "
            + "(block_entity, contents, named, villager, hostile, hazard_item, near(owner)), ids (minecraft:chest), "
            + "tags (#c:chests) with & | ! and parentheses; @Name limits a row to one companion.")
    @SeeAlso({"numen.permission.deny", "numen.permission.ask", "numen.permission.allow", "numen.permission.remove"})
    public static Rules rules(ServerCall call) {
        NumenPlayer her = call.her();
        RuleBook book = RuleBook.of(storeOf(her), PermissionConfig.of(her.getServer()).hardRules());
        List<String> hard = new ArrayList<>();
        book.hard().forEach(row -> hard.add(line(row.table(), row.rule())));
        List<String> yours = new ArrayList<>();
        for (Verdict.Kind table : List.of(Verdict.Kind.DENY, Verdict.Kind.ALLOW, Verdict.Kind.ASK)) {
            book.owner().get(table).forEach(row -> yours.add(line(table, row.rule()) + note(row)));
        }
        List<String> factory = new ArrayList<>();
        for (Verdict.Kind table : List.of(Verdict.Kind.ALLOW, Verdict.Kind.ASK)) {
            book.factory().get(table).forEach(row -> factory.add(line(table, row.rule())));
        }
        return new Rules(hard, yours, factory);
    }

    /** 加一行。 */
    public record Add(@Doc("The rule, verb(condition), optionally followed by @Name for one companion. "
            + "E.g. break(owner & #c:chests).") String rule,
                      @Doc("Why you add it; your owner sees it next to the row.")
                      @Omitted("no reason is noted") Optional<String> why) {}

    /** 改了什么。 */
    public record Changed(@Doc("The row, as numen.permission.rules() lists it.") String row,
                          @Doc("false when your owner's rules already had exactly this row, or the row to remove "
                                  + "was already gone.") boolean changed,
                          @Doc("true when your owner was in the game and has been told about a row you added "
                                  + "yourself; otherwise he sees it, marked as yours, in his rule list.") boolean told) {}

    @Fn("Add a deny row to your owner's rules: what matches is refused from now on, whatever else allows it.")
    @Example("numen.permission.deny(\"break(owner & #c:chests)\", {why = \"you asked me to leave the chests alone\"})")
    @Note("Takes effect at once and does not ask: tightening needs no consent. Your owner is told.")
    @Note("To loosen, use numen.permission.allow, which asks your owner.")
    @SeeAlso({"numen.permission.rules", "numen.permission.ask"})
    public static Changed deny(ServerCall call, Add args) {
        return tighten(call, Verdict.Kind.DENY, args);
    }

    @Fn("Add an ask row to your owner's rules: what matches is asked of your owner first, from now on.")
    @Example("numen.permission.ask(\"break(nature & minecraft:oak_log)\", {why = \"the trees by the house are his\"})")
    @Note("Takes effect at once and does not ask: tightening needs no consent. Your owner is told.")
    @SeeAlso({"numen.permission.rules", "numen.permission.deny"})
    public static Changed ask(ServerCall call, Add args) {
        return tighten(call, Verdict.Kind.ASK, args);
    }

    @Fn("Ask your owner to add an allow row: what matches no longer needs asking.")
    @Example("numen.permission.allow(\"break(owner & minecraft:wheat)\", {why = \"he told me to harvest the farm\"})")
    @Note("Loosening is your owner's call: this puts a card in front of him and waits. Written only if he allows; "
            + "\"allow and remember\" is not offered. A deny anywhere still wins over this row.")
    @SeeAlso({"numen.permission.rules", "numen.permission.remove"})
    public static Pending<Changed> allow(ServerCall call, Add args) {
        Rule rule = parse(args.rule());
        String line = "numen permission rules add allow " + rule;
        return call.consent(ConsentItem.ruleEdit(line, "")).then(allowed ->
                write(call, Verdict.Kind.ALLOW, rule, args.why()));
    }

    /** 删一行。 */
    public record Remove(@Doc("The row exactly as numen.permission.rules() lists it, e.g. \"deny break(owner)\" "
            + "(the part after it, who wrote it and how often it fired, left out).") String row) {}

    @Fn("Ask your owner to remove one of his rows.")
    @Example("numen.permission.remove(\"deny break(owner & #c:chests)\")")
    @Note("Taking a row away is your owner's call whichever table it is in: this puts a card in front of him and "
            + "waits. Only his layer has rows to remove; the hard and factory rules are not his to remove.")
    @SeeAlso("numen.permission.rules")
    public static Pending<Changed> remove(ServerCall call, Remove args) {
        PermissionStore store = requiredStore(call.her());
        String[] words = args.row().split(NOTE_MARK, 2)[0].strip().split("\\s+", 2);
        Verdict.Kind table = words.length == 2 ? tableOf(words[0]) : null;
        if (table == null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "write the row as the list shows it: \"deny break(owner)\", "
                    + "\"ask …\" or \"allow …\" and the rule: '" + args.row() + "'", Call.of("numen.permission.rules"));
        }
        Rule rule = parse(words[1]);
        int at = store.indexOf(table, rule);
        if (at < 0) {
            throw new ApiError(ErrorKind.NOT_FOUND, "your owner's rules have no " + line(table, rule),
                    Call.of("numen.permission.rules"));
        }
        String command = "numen permission rules remove " + tableWord(table) + " " + (at + 1);
        return call.consent(ConsentItem.ruleEdit(command, rule.toString())).then(allowed -> {
            int now = store.indexOf(table, rule);
            if (now >= 0) {
                store.remove(table, now);
            }
            return new Changed(line(table, rule), now >= 0, false);
        });
    }

    // ==================== 内部 ====================

    /** 收紧的行直接写进去、回执说清、也直接告诉主人。 */
    private static Changed tighten(ServerCall call, Verdict.Kind table, Add args) {
        Rule rule = parse(args.rule());
        Changed written = write(call, table, rule, args.why());
        ServerPlayer owner = call.her().resolveOwnerPlayer();
        boolean told = written.changed() && owner != null;
        if (told) {
            owner.sendSystemMessage(Component.translatable(ModLanguageData.Keys.PERMISSION_TIGHTENED,
                    call.her().getName(), tableWord(table), rule.toString()));
        }
        return new Changed(written.row(), written.changed(), told);
    }

    /** 写进主人层,来源是她。 */
    private static Changed write(ServerCall call, Verdict.Kind table, Rule rule, Optional<String> why) {
        PermissionStore store = requiredStore(call.her());
        boolean added = store.add(table, new PermissionStore.Row(rule, PermissionStore.Source.HER,
                why.map(String::strip).orElse("")));
        return new Changed(line(table, rule), added, false);
    }

    private static Rule parse(String text) {
        try {
            return Groups.parse(text);
        } catch (Rule.Mistake mistake) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, mistake.getMessage(),
                    Call.of("numen.permission.deny", "break(owner & #c:chests)"));
        }
    }

    private static PermissionStore storeOf(NumenPlayer her) {
        UUID owner = her.getOwnerUuid();
        return owner == null ? null : PermissionStore.of(her.getServer(), owner);
    }

    private static PermissionStore requiredStore(NumenPlayer her) {
        PermissionStore store = storeOf(her);
        if (store == null) {
            throw new ApiError(ErrorKind.FAILED, "you have no owner whose rules these could be", null);
        }
        return store;
    }

    private static String line(Verdict.Kind table, Rule rule) {
        return tableWord(table) + " " + rule;
    }

    private static String tableWord(Verdict.Kind table) {
        return table.name().toLowerCase(Locale.ROOT);
    }

    private static Verdict.Kind tableOf(String word) {
        for (Verdict.Kind table : Verdict.Kind.values()) {
            if (tableWord(table).equals(word)) {
                return table;
            }
        }
        return null;
    }

    /** 列表里一行后面的注:她写的(带理由)和命中几次。 */
    private static String note(RuleBook.Line row) {
        PermissionStore.Hit hit = row.hit();
        StringBuilder sb = new StringBuilder();
        if (row.byHer()) {
            sb.append(NOTE_MARK).append("you added it").append(row.note().isEmpty() ? "" : ": " + row.note());
        }
        sb.append(sb.isEmpty() ? NOTE_MARK : "; ").append(hit.count() == 0 ? "never fired" : "fired " + hit.count() + "x");
        return sb.toString();
    }
}
