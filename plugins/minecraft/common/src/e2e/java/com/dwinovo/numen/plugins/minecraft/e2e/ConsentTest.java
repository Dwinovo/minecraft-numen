package com.dwinovo.numen.plugins.minecraft.e2e;

import com.dwinovo.numen.api.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.dwinovo.numen.plugins.minecraft.e2e.FakeModel.lua;
import static com.dwinovo.numen.plugins.minecraft.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 她要做一件需要征询的事(丢东西默认要问主人):征询只推给主人的客户端,主人在那里答([允许],发回的就是答复框发的那个包)以后她接着做完,
 * 并告诉他。
 */
class ConsentTest extends Scenario {

    @Test
    void a_consent_request_reaches_the_owners_client_and_allowing_it_lets_her_carry_on() throws Exception {
        HeadlessOwner owner = joinOwner("e2e_approver", "en_us");
        UUID rin = summon(owner, "Rin");
        assertTrue(console("give Rin minecraft:gold_ingot 4").contains("Rin"), "the gold ingots were not given");
        MODEL.whenHeard("drop the gold", lua("minecraft.inv.drop(\"minecraft:gold_ingot\", {count = 4})"),
                say("Dropped the gold."));

        owner.says(rin, "drop the gold");

        ConsentRequestPayload ask = owner.awaitConsent();
        assertEquals(rin, ask.companion());
        assertTrue(!ask.lines().isEmpty() && ask.lines().get(0).count() == 4, "the request does not say what she wants: " + ask);

        owner.answer(ask, ConsentAnswer.Decision.ALLOW_ONCE);

        owner.until("her words after she dropped the gold", () -> owner.loggedWords(rin, "Dropped the gold."));
        assertTrue(console("clear Rin minecraft:gold_ingot 0").contains("No items"),
                "the gold is still in her inventory after she was allowed to drop it");
    }
}
