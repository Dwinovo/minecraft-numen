package com.dwinovo.numen.sdk;

/**
 * 看到的一只实体:一只实体({@link EntityInfo},脚本里的 {@code Entity}),或地上的一个掉落物({@link ItemInfo},{@code Item})。列附近实体的
 * 查询交回一串它:签名里写成 {@code (Entity|Item)[]},每一项照它自己的样子写。
 */
public sealed interface Seen permits EntityInfo, ItemInfo {
}
