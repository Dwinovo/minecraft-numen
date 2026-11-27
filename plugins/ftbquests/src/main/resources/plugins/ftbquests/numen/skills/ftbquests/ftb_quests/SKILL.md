---
name: ftb_quests
description: How FTB Quests and FTB Teams work — how quest progress is credited to a team, which tasks count when you do them, which must be handed in or done by someone else, how rewards are given, team invites and joining rules, what FTB Chunks claims allow you, and the quest_completed, quest_reward_auto and team_invite events you receive.
---

# FTB Quests and Teams

This world has FTB Quests installed. FTB treats you as a real player: the mobs you kill, what you carry and where you walk all count toward the quest progress of **the team you are in**, by FTB's own rules. The functions are in the `ftbquests` group; look up how to call them with ApiSearch (select:ftbquests.quest.submit) and the like.

## Who gets the progress

Progress is counted per team only:

- **In the same team as your owner**: what you do is what they do, and you advance the same quest book together.
- **Not in the same team**: what you do counts for your own team only, and your owner's team does not move at all.

The quest book is your owner's: the progress is their team's, and titles are shown in their language. The quest list says at the top whether you are in that team.

## Who can complete which task

| Mark in the list | Meaning |
|---|---|
| you can do it too | Kill, reach, or carry something — it counts when you do it |
| hand in with submit | Needs "submit" in the book (handing in items, experience, or ticking a box); submitting takes things from **your inventory**, and what is handed in does not come back |
| observation | The target has to be looked at in person; only the player themself can complete it |
| task screen / crafted / modpack's scripts | Needs a task screen block, only counts at the moment of crafting, or is judged by the modpack's own scripts |

Submitting a quest that is not unlocked is refused, and the receipt gives the reason; a submit receipt gives each task's progress before and after, and what your inventory actually lost.

## Rewards

FTB's auto-claim works for you the same as for a human player:

- **Personal rewards**: one for every online member, you included, straight into your inventory.
- **Team rewards**: given only to the one FTB counts as having completed the quest, which may be you or your owner.
- What does not fit in a full inventory drops at your feet.

When rewards are credited to you, you receive a `quest_reward_auto` event that says which rewards were claimed and how your inventory and experience actually changed at that moment. When the team completes a quest or a chapter you receive `quest_completed`, where `by` is the one FTB counts as having completed it.

## Teams

- When someone invites you to a team you receive `team_invite`, which says which team (`party`) and whether your owner is in it. Accept with `ftbquests.quest.join`; with several invites at once, name the one to join with `party`.
- You cannot join a new team while you are in another one; leave the old one first.

## Claims (FTB Chunks)

- In your owner's team, their claims let you through as a member.
- In a team of your own, you can dig, place and open chests in a claim only if your owner adds you as an ally or the claim is set to public.
