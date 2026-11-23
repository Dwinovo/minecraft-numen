package com.dwinovo.numen.api.describe;

import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.agent.scope.Handle;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 问一圈"这是什么":登记着的回答者挨个问,认得的各说几个字段、归在各自的名字下、按登记先后;不认得的不占名字,出错的只丢它自己那一条。
 * 名字在脚本里写不出来、或和已有的重名,登记当场失败。
 */
class DescriptionsTest {

    private static final BlockPos AT = BlockPos.ZERO;

    /** 按先后登记几个回答者跑一段,不论成败都撤掉:这张表是全进程共用的。 */
    private static void with(LinkedHashMap<String, Describer<BlockPos>> describers, Runnable body) {
        List<Handle> handles = new ArrayList<>();
        try {
            describers.forEach((name, describer) -> handles.add(Questions.BLOCK.register(name, describer)));
            body.run();
        } finally {
            handles.forEach(Handle::close);
        }
    }

    @Test
    void everyoneWhoKnowsAnswersUnderItsOwnNameInRegistrationOrder() {
        LinkedHashMap<String, Describer<BlockPos>> describers = new LinkedHashMap<>();
        describers.put("gt_storage", (her, pos) -> Map.of("items", List.of(Map.of("item", "minecraft:raw_iron", "count", 8))));
        describers.put("gt_stranger", (her, pos) -> null);
        describers.put("gt_smelter", (her, pos) -> Map.of("running", true));
        with(describers, () -> {
            Map<String, Map<String, Object>> said = Descriptions.of(Questions.BLOCK, null, AT);
            assertEquals(List.of("gt_storage", "gt_smelter"), List.copyOf(said.keySet()));
            // 回答当场写成脚本的普通值:整数读回是 Long
            assertEquals(Map.of("items", List.of(Map.of("item", "minecraft:raw_iron", "count", 8L))),
                    said.get("gt_storage"));
            assertEquals(Map.of("running", true), said.get("gt_smelter"));
        });
    }

    @Test
    void answerersWithNothingToSayTakeNoName() {
        LinkedHashMap<String, Describer<BlockPos>> describers = new LinkedHashMap<>();
        describers.put("gt_silent", (her, pos) -> null);
        describers.put("gt_empty", (her, pos) -> Map.of());
        with(describers, () -> assertEquals(Map.of(), Descriptions.of(Questions.BLOCK, null, AT)));
    }

    @Test
    void anAnswererThatThrowsLosesOnlyItsOwnEntry() {
        LinkedHashMap<String, Describer<BlockPos>> describers = new LinkedHashMap<>();
        describers.put("gt_broken", (her, pos) -> {
            throw new IllegalStateException("broken");
        });
        describers.put("gt_sound", (her, pos) -> Map.of("note", "still here"));
        with(describers, () -> assertEquals(Map.of("gt_sound", Map.of("note", "still here")),
                Descriptions.of(Questions.BLOCK, null, AT)));
    }

    @Test
    void aSecondAnswererUnderTheSameNameIsRefusedAtRegistration() {
        Handle first = Questions.BLOCK.register("gt_twice", (her, pos) -> null);
        try {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> Questions.BLOCK.register("gt_twice", (her, pos) -> null));
            assertTrue(refused.getMessage().contains("gt_twice"), refused.getMessage());
        } finally {
            first.close();
        }
    }

    @Test
    void aNameAScriptCannotWriteAsAFieldIsRefusedAtRegistration() {
        for (String name : new String[] {"", "Smelter", "my-smelter", "my smelter", "9lives", "end"}) {
            assertThrows(IllegalArgumentException.class, () -> Questions.BLOCK.register(name, (her, pos) -> null),
                    "'" + name + "' was taken as an answerer's name");
        }
        assertThrows(IllegalArgumentException.class, () -> Questions.BLOCK.register(null, (her, pos) -> null));
    }
}
