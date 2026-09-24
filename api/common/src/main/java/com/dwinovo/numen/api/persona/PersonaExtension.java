package com.dwinovo.numen.api.persona;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Adds an opaque, namespaced data block to Numen personas.
 *
 * <p>Numen stores the data with the persona, removes it from the prompt text,
 * forwards it with a summon request, and calls {@link #onSummon} on the server.
 * The extension owns the data format and its meaning; Numen does not inspect it.
 */
public interface PersonaExtension {

    /** Stable namespaced key, for example {@code examplemod:character_defaults}. */
    String id();

    /** Translation key shown above this extension's generic multiline editor. */
    String editorLabelKey();

    /** Translation key used as the editor's placeholder text. */
    String editorPlaceholderKey();

    /** Maximum editor text length. Defaults data must also be validated server-side. */
    default int editorMaxLength() {
        return 4096;
    }

    /** Extract this extension's data from Markdown and return the prompt text without it. */
    Parsed parse(String markdown);

    /** Add this extension's data to Markdown while preserving the freeform prompt. */
    String compose(String promptText, String data);

    /** Apply the opaque persona data to a newly summoned or woken companion. */
    void onSummon(ServerPlayer owner, NumenPlayer companion, String data);

    record Parsed(String promptText, String data) {
        public Parsed {
            promptText = promptText == null ? "" : promptText;
            data = data == null ? "" : data;
        }
    }
}
