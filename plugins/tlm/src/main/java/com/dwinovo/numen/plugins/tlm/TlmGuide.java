package com.dwinovo.numen.plugins.tlm;

/**
 * {@code tlm} 的说明(Markdown):车万女仆(Touhou Little Maid)联动,她经 {@code ApiSearch} 读到的第一份。函数各自的签名与说明由登记生成,
 * 这里讲它们之外的:这个插件干什么、各组怎么配合。
 */
final class TlmGuide {

    private TlmGuide() {}

    static final String TEXT = """
            # tlm

            Touhou Little Maid (TLM) adds maids: companions that a player tames, equips and sets to work. This plugin lets you raise maids the way your owner does, and wear a maid model yourself.

            ## How the groups fit
            - `tlm.maid` is the keeping of maids. `tlm.maid.list()` shows the maids that are yours (nearby, in unloaded chunks, and tombstones) with the ids every other function takes; `tlm.maid.info(id)` shows one maid in full, with whether each work mode can be switched to right now and what it is missing. The rest changes her: `tlm.maid.task` (work mode), `tlm.maid.config` (home, pickup, schedule and the config page), `tlm.maid.targets` and `tlm.maid.set_targets` (whom she attacks), `tlm.maid.name`, `tlm.maid.model` and `tlm.maid.set_model`, and `tlm.maid.give` and `tlm.maid.take` to hand items over and back. These act within about 7 blocks of her, so stand beside her first.
            - `tlm.skin` is your own look: `tlm.skin.list()` shows the installed maid models, `tlm.skin.wear(model)` puts one on you and `tlm.skin.remove()` takes it off. It covers your whole body, so a YSM model stops showing until you remove it; it does not ask your owner, so tell them what you changed into.
            - `tlm.altar.recipes()` lists what the altar can craft, with its power points.

            ## Things to know
            - A wild maid is tamed with a cake (`minecraft.use.entity` on her, with the cake), after which a `maid_tamed` event tells you she is yours. A maid's id comes from `minecraft.scan.entities` or `tlm.maid.list`, and changes after a server restart.
            - Your power points and how many maids TLM counts as yours are in your body state every turn.
            - TLM decides what a maid may do: only the owner may switch her work mode, and a mode may wait for something first. Using your maid can ask your owner first.
            - Read the `maid_keeping` and `maid_look` skills for taming, gear and baubles, work modes, schedules and reviving a maid.

            ## Example
            ```lua
            local maid = tlm.maid.list().here[1]
            tlm.maid.task("touhou_little_maid:farm", {maid = maid.id})
            ```
            """;
}
