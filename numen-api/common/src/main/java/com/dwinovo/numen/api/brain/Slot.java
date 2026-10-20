package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.voice.VoiceProfile;

/**
 * 她身上绑定的三项库条目:模型档案、人设、声线。每一项是一种库({@link #kind})里的一条,她的绑定({@code binding.json})记的
 * 是条目的名字({@code 命名空间:名字})。面板的编辑卡、{@code /numen companion <她> profile|persona|voice <条目>} 和召唤之后的
 * 每一次改绑都经 {@link OwnerInput.Bind} 走同一个入口({@link CompanionBinding})。皮肤不是库条目——它是文件夹里的 png,
 * 穿的是签名缓存里的哪一张记在同一个绑定文件里({@link CompanionLoop#wearSkin}),换皮肤走 {@code Wardrobe}。
 */
public enum Slot {
    PROVIDER("profile", Libraries.PROVIDER, ProviderLibrary.KIND),
    PERSONA("persona", Libraries.PERSONA, PersonaLibrary.KIND),
    VOICE("voice", Libraries.VOICE, VoiceProfile.KIND);

    private final String command;
    private final String wire;
    private final LibraryKind kind;

    Slot(String command, String wire, LibraryKind kind) {
        this.command = command;
        this.wire = wire;
        this.kind = kind;
    }

    /** 命令里这一项叫什么。 */
    public String command() {
        return command;
    }

    /** 线上、绑定文件里、库协议里这一项叫什么(也是它那种库的名字)。 */
    public String wire() {
        return wire;
    }

    /** 这一项的条目住在哪一种库里。 */
    public LibraryKind kind() {
        return kind;
    }

    /** 读线上的写法;不认得是 {@code null}。 */
    public static Slot ofWire(String wire) {
        for (Slot slot : values()) {
            if (slot.wire.equals(wire)) {
                return slot;
            }
        }
        return null;
    }
}
