package com.dwinovo.numen.api.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 旧信号名换成归属组:只改作为一项出现的整词。 */
class RuleMigrationTest {

    @Test
    void everyOldSignalBecomesAnOwnershipGroup() {
        assertEquals("break((owner | other) & !block_entity)", RuleMigration.rewrite("break(placed & !block_entity)"));
        assertEquals("break(self & !contents)", RuleMigration.rewrite("break(self_placed & !contents)"));
        assertEquals("attack((self | owner | other))", RuleMigration.rewrite("attack(owned)"));
        assertEquals("use_entity(self)", RuleMigration.rewrite("use_entity(self_owned)"));
        assertEquals("place(hazard_item & !(near(owner) | near(other)))",
                RuleMigration.rewrite("place(hazard_item & !near_placed)"));
        assertEquals("break(!(owner | other))", RuleMigration.rewrite("break(!placed)"));
    }

    @Test
    void namesInsideIdsTagsAndOtherWordsStayAsTheyAre() {
        assertEquals("break(minecraft:placed_block)", RuleMigration.rewrite("break(minecraft:placed_block)"));
        assertEquals("break(#c:placed)", RuleMigration.rewrite("break(#c:placed)"));
        assertEquals("break(owner)", RuleMigration.rewrite("break(owner)"));
        assertEquals("command(owned_thing)", RuleMigration.rewrite("command(owned_thing)"));
    }
}
