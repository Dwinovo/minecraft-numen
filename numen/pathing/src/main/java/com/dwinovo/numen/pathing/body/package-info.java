/**
 * 身体:寻路模块在一具服务端玩家身上做事的这一层,只有一个端口 {@link com.dwinovo.numen.pathing.body.Body}。键盘({@code Controls})、
 * 视角({@code Look})、鼠标({@code Mouse})、快捷栏({@code Hotbar})与每刻的物理步进({@code Physics})是假玩家缺的那半个
 * 客户端,属于 Numen API(包 {@code com.dwinovo.numen.api.entity}),寻路只按它们用,不另写一套手。
 *
 * <p>只在世界所在的线程上用。
 */
package com.dwinovo.numen.pathing.body;
