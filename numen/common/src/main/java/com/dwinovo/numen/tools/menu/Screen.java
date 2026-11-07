package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.tools.inventory.InvApi.Stack;

import java.util.List;
import java.util.Optional;

/**
 * 一个界面按它的种类说出的话:{@code numen.gui.view().screen}。格子和菜单里的原始数字(进度、费用、等级)在界面种类之间意思不同,
 * 这里按种类写成有含义的字段;选项带编号(从 1 起),就是 {@code numen.gui.choose}、{@code numen.gui.page} 收的那个数。每种界面怎么读见
 * {@link MenuKind},这里只放它们读出来的样子。
 */
public sealed interface Screen permits Screen.Enchanting, Screen.Stonecutting, Screen.Weaving, Screen.Trading,
        Screen.Beacon, Screen.Anvil, Screen.Lectern, Screen.Crafter {

    /** 原版附魔台只露出的一个提示:这一项会附上的其中一个附魔(实际附上的可能还有别的)。 */
    @Doc("The one enchantment the table shows for an option.")
    record EnchantHint(@Doc("Its id, minecraft:sharpness.") String enchantment, @Doc("Its level.") int level) {}

    /** 附魔台的一个选项。 */
    @Doc("One of the three enchanting options.")
    record EnchantOption(@Doc("The number numen.gui.choose takes.") int n,
                         @Doc("Experience levels it asks for. It also takes n lapis lazuli and needs n levels; creative "
                                 + "mode needs neither.") int cost,
                         @Doc("False when the table offers nothing for the item in it at this option.") boolean offered,
                         Optional<EnchantHint> hint) {}

    /** 附魔台。 */
    @Doc("An enchanting table: its three options, lapis lazuli in it and your levels.")
    record Enchanting(List<EnchantOption> options,
                      @Doc("Lapis lazuli in the table's slot.") int lapis,
                      @Doc("Your experience levels.") int levels) implements Screen {}

    /** 切石机的一个配方。 */
    @Doc("One recipe the stonecutter offers for its input.")
    record Cut(@Doc("The number numen.gui.choose takes.") int n,
               @Doc("What one cut makes.") Stack makes) {}

    /** 切石机。 */
    @Doc("A stonecutter: the recipes for the block in its input slot.")
    record Stonecutting(List<Cut> recipes, @Doc("The recipe selected now.") Optional<Integer> selected)
            implements Screen {}

    /** 织布机的一个图案。 */
    @Doc("One pattern the loom can weave onto the banner.")
    record WeavePattern(@Doc("The number numen.gui.choose takes.") int n, @Doc("Its id, minecraft:stripe_bottom.") String name) {}

    /** 织布机。 */
    @Doc("A loom: the patterns for the banner, dye and pattern item in it.")
    record Weaving(List<WeavePattern> patterns, @Doc("The pattern selected now.") Optional<Integer> selected)
            implements Screen {}

    /** 村民或流浪商人的一笔交易。 */
    @Doc("One trade a villager or wandering trader offers.")
    record Trade(@Doc("The number numen.gui.choose takes.") int n,
                 @Doc("What it asks for: one item, or two.") List<Stack> pay,
                 @Doc("What it gives.") Stack get,
                 @Doc("How many times it has been made since the last restock.") int used,
                 @Doc("How many times it can be made before it is sold out.") int max,
                 @Doc("Sold out: used reached max.") boolean locked) {}

    /** 交易。 */
    @Doc("A trading window: every trade on offer.")
    record Trading(List<Trade> trades,
                   @Doc("The villager's level (novice 1 to master 5); a wandering trader has none.") Optional<Integer> level,
                   @Doc("The villager's experience points.") Optional<Integer> xp) implements Screen {}

    /** 信标。 */
    @Doc("A beacon: its pyramid and the effects it can be set to.")
    record Beacon(@Doc("Pyramid levels, 0 to 4.") int levels,
                  @Doc("A payment item (iron, gold, emerald, diamond or netherite) is in its slot.") boolean paid,
                  @Doc("The primary effects the pyramid allows: haste, speed, ...") List<String> primaries,
                  @Doc("The secondary effects it allows (4 levels): the primary again, or one of these.")
                  List<String> secondaries,
                  @Doc("The primary effect set now.") Optional<String> primary,
                  @Doc("The secondary effect set now.") Optional<String> secondary) implements Screen {}

    /** 铁砧。 */
    @Doc("An anvil: what taking the result costs.")
    record Anvil(@Doc("Experience levels taking the result costs.") int cost,
                 @Doc("What the result slot holds.") Optional<Stack> result,
                 @Doc("The result's name.") Optional<String> name) implements Screen {}

    /** 讲台。 */
    @Doc("A lectern with a book on it.")
    record Lectern(Optional<String> title, Optional<String> author,
                   @Doc("The page it is open at, counted from 1.") int page,
                   @Doc("How many pages the book has.") int pages,
                   @Doc("This page's text.") Optional<String> text) implements Screen {}

    /** 合成器的一格。 */
    @Doc("One slot of the crafter's 3x3 grid.")
    record CrafterSlot(@Doc("The window slot, what numen.gui.toggle takes.") int slot,
                       @Doc("False when the slot is switched off and takes nothing.") boolean open) {}

    /** 合成器。 */
    @Doc("A crafter: which of its nine slots are switched on.")
    record Crafter(List<CrafterSlot> slots, @Doc("A redstone signal is on it, so it crafts when ticked.") boolean powered)
            implements Screen {}
}
