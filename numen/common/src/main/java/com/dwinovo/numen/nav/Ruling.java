package com.dwinovo.numen.nav;

import java.util.List;

import com.dwinovo.numen.api.permission.ConsentItem;
import com.dwinovo.numen.api.sdk.Doc;

import net.minecraft.core.BlockPos;

/**
 * 路上权限层或主人对一格改动的裁定:问过主人、答应了或不答应,或规则当场不许。路程回执({@link Walked})里照实列出。
 *
 * @param pos     那一格
 * @param what    她要在那里做的事,{@code break oak_planks}
 * @param allowed 放行了
 * @param by      谁裁的、怎么说的:{@code your owner}、{@code your owner: <原话>}、{@code the rules: <规则的理由>}
 */
@Doc("What your owner or the permission layer ruled about a cell the walk wanted to change.")
public record Ruling(@Doc("The cell.") BlockPos pos,
                     @Doc("What the walk wanted to do there, break oak_planks.") String what,
                     @Doc("It was allowed. Otherwise the walk kept to other cells.") boolean allowed,
                     @Doc("Who ruled and what they said: your owner, your owner: <their words>, or the rules: <the "
                             + "reason>.") String by) {

    public Ruling {
        pos = pos.immutable();
    }

    /** 主人答应了问到的这几条。 */
    public static List<Ruling> allowed(List<ConsentItem> asked) {
        return rulings(asked, true, "your owner");
    }

    /** 主人不答应问到的这几条,{@code words} 是他的原话。 */
    public static List<Ruling> refusedByOwner(List<ConsentItem> asked, String words) {
        return rulings(asked, false, words.isBlank() ? "your owner" : "your owner: " + words);
    }

    /** 规则当场不许改 {@code pos}(那里此刻是 {@code block}),{@code reason} 是规则的理由。 */
    public static Ruling refusedByRules(BlockPos pos, String block, String reason) {
        return new Ruling(pos, "change " + block, false, "the rules: " + reason);
    }

    private static List<Ruling> rulings(List<ConsentItem> asked, boolean allowed, String by) {
        return asked.stream().filter(item -> item.pos() != null)
                .map(item -> new Ruling(item.pos(), item.kind().word() + " " + item.subject(), allowed, by)).toList();
    }
}
