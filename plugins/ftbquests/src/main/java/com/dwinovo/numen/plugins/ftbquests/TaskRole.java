package com.dwinovo.numen.plugins.ftbquests;

import dev.ftb.mods.ftbquests.quest.task.AdvancementTask;
import dev.ftb.mods.ftbquests.quest.task.BiomeTask;
import dev.ftb.mods.ftbquests.quest.task.CheckmarkTask;
import dev.ftb.mods.ftbquests.quest.task.DimensionTask;
import dev.ftb.mods.ftbquests.quest.task.EnergyTask;
import dev.ftb.mods.ftbquests.quest.task.FluidTask;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.KillTask;
import dev.ftb.mods.ftbquests.quest.task.LocationTask;
import dev.ftb.mods.ftbquests.quest.task.ObservationTask;
import dev.ftb.mods.ftbquests.quest.task.StageTask;
import dev.ftb.mods.ftbquests.quest.task.StatTask;
import dev.ftb.mods.ftbquests.quest.task.StructureTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.quest.task.XPTask;

/**
 * 一个任务条件由谁、怎么完成——list、show 给她看的"谁来完成",submit 决定交哪几个,都只问这一处。
 *
 * <p>判据是 FTB 自己对这种条件的处理方式:
 * <ul>
 *   <li>FTB 每刻或在事件里自己检测的(击杀、到场、持有物品、统计、成就……):她做到的照样算,{@link #COUNTS}。</li>
 *   <li>要点任务书上的按钮、服务端按 {@code SubmitTaskMessage} 调 {@code submitTask} 才推进的(消耗型物品、
 *       经验、打勾):{@link #SUBMIT},{@code submit} 代她走的就是这条。</li>
 *   <li>只认合成那一刻的物品({@code only_from_crafting}):{@link #CRAFTED},身上已有的不算,提交也不算。</li>
 *   <li>观察:判定在玩家自己的客户端(准星对没对准),服务端直接提交就跳过了"她确实在看",{@link #OBSERVE}。</li>
 *   <li>只能经任务屏方块交的(流体、能量、标了 task_screen_only 的物品):{@link #SCREEN}。</li>
 *   <li>自定义条件和别的模组加的条件类型:怎么推进由整合包脚本或那个模组决定,这里看不透,{@link #EXTERNAL}。</li>
 * </ul>
 */
enum TaskRole {

    COUNTS("you can do it too"),
    SUBMIT("hand in with submit"),
    CRAFTED("counts only items at the moment they are crafted"),
    OBSERVE("observation, not supported for you yet"),
    SCREEN("handed in through a task screen block"),
    EXTERNAL("decided by the modpack's scripts or another mod");

    private final String label;

    TaskRole(String label) {
        this.label = label;
    }

    static TaskRole of(Task task) {
        return switch (task) {
            case ObservationTask ignored -> OBSERVE;
            case CheckmarkTask ignored -> SUBMIT;
            case XPTask ignored -> SUBMIT;
            case ItemTask item when item.isTaskScreenOnly() -> SCREEN;
            case ItemTask item when item.consumesResources() -> SUBMIT;
            case ItemTask item when item.isOnlyFromCrafting() -> CRAFTED;
            case ItemTask ignored -> COUNTS;
            case FluidTask ignored -> SCREEN;
            case EnergyTask ignored -> SCREEN;
            case KillTask ignored -> COUNTS;
            case LocationTask ignored -> COUNTS;
            case BiomeTask ignored -> COUNTS;
            case DimensionTask ignored -> COUNTS;
            case StructureTask ignored -> COUNTS;
            case StatTask ignored -> COUNTS;
            case AdvancementTask ignored -> COUNTS;
            case StageTask ignored -> COUNTS;
            default -> EXTERNAL;
        };
    }

    /** 列表与详情里跟在条件后面的那几个词。 */
    String label() {
        return label;
    }
}
