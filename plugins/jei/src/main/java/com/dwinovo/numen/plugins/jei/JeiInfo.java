package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.api.knowledge.InfoPage;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Fn;

/** {@code info} 的来源:主人客户端上的 JEI 里,模组或整合包为这样东西写的说明页。 */
public final class JeiInfo {

    private JeiInfo() {}

    @Fn("The description pages written for an item, from your owner's JEI.")
    public static InfoPage read(ClientCall call, JeiQuery args) {
        return JeiLookups.info(args.item());
    }
}
