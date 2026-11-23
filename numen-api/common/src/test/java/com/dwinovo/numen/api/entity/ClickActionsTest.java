package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.api.permission.Action;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 问一圈"这一下右键会对世界做什么":登记着的回答者挨个问,各人说的动作按登记先后合在一起;不认得的什么也不添,出错的只丢它自己那一条。
 */
class ClickActionsTest {

    private static final Action FIRST = Action.breakBlock(new BlockPos(1, 64, 1), null);
    private static final Action SECOND = Action.breakBlock(new BlockPos(2, 64, 2), null);
    private static final Action THIRD = Action.breakBlock(new BlockPos(3, 64, 3), null);

    /** 按先后登记几个回答者跑一段,不论成败都撤掉:这张表是全进程共用的。 */
    private static void with(LinkedHashMap<String, ClickActions> answerers, Runnable body) {
        List<Handle> handles = new ArrayList<>();
        try {
            answerers.forEach((name, answerer) -> handles.add(Questions.CLICK.register(name, answerer)));
            body.run();
        } finally {
            handles.forEach(Handle::close);
        }
    }

    @Test
    void everyoneWhoKnowsAddsItsActionsInRegistrationOrder() {
        LinkedHashMap<String, ClickActions> answerers = new LinkedHashMap<>();
        answerers.put("gt_two", (body, hit, stack) -> List.of(FIRST, SECOND));
        answerers.put("gt_stranger", (body, hit, stack) -> List.of());
        answerers.put("gt_silent", (body, hit, stack) -> null);
        answerers.put("gt_one", (body, hit, stack) -> List.of(THIRD));
        with(answerers, () -> assertEquals(List.of(FIRST, SECOND, THIRD), ClickActions.of(null, null, null)));
    }

    @Test
    void withNobodyRegisteredTheClickDoesNothingToAsk() {
        assertEquals(List.of(), ClickActions.of(null, null, null));
    }

    @Test
    void anAnswererThatThrowsLosesOnlyItsOwnActions() {
        LinkedHashMap<String, ClickActions> answerers = new LinkedHashMap<>();
        answerers.put("gt_sound", (body, hit, stack) -> List.of(FIRST));
        answerers.put("gt_broken", (body, hit, stack) -> {
            throw new IllegalStateException("broken");
        });
        answerers.put("gt_holey", (body, hit, stack) -> Arrays.asList(SECOND, null));
        answerers.put("gt_later", (body, hit, stack) -> List.of(THIRD));
        with(answerers, () -> assertEquals(List.of(FIRST, THIRD), ClickActions.of(null, null, null)));
    }
}
