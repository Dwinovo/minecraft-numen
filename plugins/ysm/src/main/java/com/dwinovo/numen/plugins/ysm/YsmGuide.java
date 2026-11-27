package com.dwinovo.numen.plugins.ysm;

/**
 * {@code ysm} 的说明(Markdown):她经 {@code ApiSearch} 读到的第一份。函数各自的签名与说明由登记生成,这里讲它们之外的:
 * 这个插件干什么、怎么配合。
 */
final class YsmGuide {

    private YsmGuide() {}

    static final String TEXT = """
            # ysm

            Yes Steve Model (YSM) changes how a player looks: a model with its own textures, and emotes that the model brings. This plugin lets you change your own look the way your owner does, and play your model's emotes.

            ## How it fits together
            - `ysm.model.options()` is where to start. It returns, at once, the model you wear now, every model you can switch to and the textures the current model brings.
            - `ysm.model.switch(model)` changes your model. Write the model id exactly as `options` lists it, never the display name; for another texture of the same model, name one of its `textures` as well. You can wear exactly the models your owner is authorized for; a refusal comes from YSM, so don't retry it, tell your owner what was refused.
            - `ysm.model.emote(name)` plays one of the current model's emotes (`extra1` to `extra8` on most models; `ysm.model.emote("stop")` stops it). YSM does not tell the server which emotes a model has, so the call returns nothing and cannot check the name; each model brings its own, and an emote ends the moment you move or switch model.
            - Read the `ysm_look` skill for the details of ids, textures, emote sounds and what the server cannot confirm.

            ## Example
            ```lua
            local options = ysm.model.options()
            print(options.current_model)
            ysm.model.switch("misc/1_alex")
            ysm.model.emote("extra1")
            ```
            """;
}
