package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.sdk.EntityLabel;

import java.util.UUID;

/**
 * 她的主人表上的一位,给界面看的样子:是谁,和他给人读的名字({@link NumenPlayer#playerName} 的口径,查不到是空串)。
 * 表本身只存在名册里({@link CompanionRegistry.Entry#keepers}),见 {@code docs/adr/runtime/0006-keepers-ownership-use-provenance.md}。
 */
public record Keeper(UUID uuid, String name) {

    public Keeper {
        name = name == null ? "" : name;
    }

    /** 界面上怎么称呼他:名字;名字查不到就写他的身份({@code player@短码})。 */
    public String shown() {
        return name.isEmpty() ? EntityLabel.shown(uuid, null) : name;
    }
}
