package com.dwinovo.numen.api.describe;

import com.dwinovo.numen.api.Focus;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.agent.scope.Handle;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 问一圈"这是什么":登记着的回答者挨个问,认得的各说几行、按登记先后接起来;不认得的不占行,出错的只丢它自己那一条。
 */
class DescriptionsTest {

    private static final Focus AT = Focus.on(BlockPos.ZERO);

    /** 登记几个回答者跑一段,不论成败都撤掉:这张表是全进程共用的。 */
    private static void with(List<Describer> describers, Runnable body) {
        List<Handle> handles = new ArrayList<>();
        try {
            for (Describer describer : describers) {
                handles.add(Questions.DESCRIBE.register(describer));
            }
            body.run();
        } finally {
            handles.forEach(Handle::close);
        }
    }

    @Test
    void everyoneWhoKnowsAddsLinesInRegistrationOrder() {
        Describer vanilla = (her, target) -> List.of("slot 0: raw_iron x8");
        Describer stranger = (her, target) -> List.of();
        Describer mod = (her, target) -> List.of("a smelter, running", "mymod.smelter works on it");
        with(List.of(vanilla, stranger, mod), () -> assertEquals(
                List.of("slot 0: raw_iron x8", "a smelter, running", "mymod.smelter works on it"),
                Descriptions.of(null, AT)));
    }

    @Test
    void nobodyKnowingIsAnEmptyList() {
        Describer stranger = (her, target) -> List.of();
        Describer silent = (her, target) -> null;
        with(List.of(stranger, silent), () -> assertEquals(List.of(), Descriptions.of(null, AT)));
    }

    @Test
    void aDescriberThatThrowsLosesOnlyItsOwnLines() {
        Describer broken = (her, target) -> {
            throw new IllegalStateException("broken");
        };
        Describer sound = (her, target) -> List.of("still here");
        with(List.of(broken, sound), () -> assertEquals(List.of("still here"), Descriptions.of(null, AT)));
    }
}
