package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.Api;

/**
 * 兼容项:同一种线协议下,各家实现细节上真有差异的那几处。按协议各一组,
 * 只收我们现在真有差异的项。缺省值怎么认出、玩家怎么覆盖,都只在 {@link Compats#resolve} 一处。
 */
public sealed interface Compat permits ChatCompat, ResponsesCompat, AnthropicCompat {

    /** 这组兼容项属于哪种协议。 */
    Api api();
}
