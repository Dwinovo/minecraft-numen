package com.dwinovo.numen.plugins.ftbquests;

/**
 * {@code ftbquests} 的说明(Markdown):FTB Quests 联动,她经 {@code ApiSearch} 读到的第一份。函数各自的签名与说明由登记生成,这里讲它们之外的:
 * 这个插件干什么、怎么配合。
 */
final class FtbQuestsGuide {

    private FtbQuestsGuide() {}

    static final String TEXT = """
            # ftbquests

            FTB Quests is a quest book, and FTB Teams decides whose progress it is. FTB treats you as a real player: what you kill, carry and walk counts toward the quest progress of the team you are in, by FTB's own rules. This plugin lets you read your owner's quest book, hand quests in, and join their party.

            ## How it fits together
            - `ftbquests.quest.list()` is your owner's book: the quests that can be worked on now, what each still needs and who can do it. It says whether you are in your owner's team; if not, what you do does not count for their book.
            - `ftbquests.quest.show(id)` is one quest in full: description, dependencies, tasks and rewards.
            - `ftbquests.quest.submit(id)` hands in a quest's items, experience or checkmarks from your own inventory; they do not come back, and it does not ask your owner. Tasks of kind kill, reach or carry count when you simply do them; observation tasks are only for the player themself.
            - `ftbquests.quest.join()` accepts a party invitation you have pending, so that you and your owner advance the same book. Join only when your owner agrees.
            - Events arrive when a quest is completed, when a reward is given to you automatically, and when a team invites you.
            - Read the `ftb_quests` skill for how progress is credited, rewards and the joining rules.

            ## Example
            ```lua
            local book = ftbquests.quest.list()
            if book.in_team and #book.quests > 0 then
              print(book.quests[1].id)
            end
            ```
            """;
}
