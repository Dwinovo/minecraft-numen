package com.dwinovo.numen.plugins.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;

/**
 * 脚本里的 {@code NumenEvents}:服务端脚本在 {@code NumenEvents.register} 里声明 Numen 插件。
 *
 * <pre>{@code
 * NumenEvents.register(event => {
 *     event.plugin('mypack', plugin => {
 *         plugin.group('talk', 'Things she can say.', group => {
 *             group.fn('greet', fn => fn
 *                 .summary('Say hello to someone by name.')
 *                 .param('who', 'string', 'The name.')
 *                 .returns('string')
 *                 .example('mypack.talk.greet("Steve")')
 *                 .run(call => 'hello ' + call.arg('who')))
 *         })
 *     })
 * })
 * }</pre>
 */
public interface NumenEvents {

    EventGroup GROUP = EventGroup.of("NumenEvents");

    /** 每次服务端脚本加载完,在上一批脚本插件撤回之后发一次。 */
    EventHandler REGISTER = GROUP.server("register", () -> RegisterKubeEvent.class);
}
