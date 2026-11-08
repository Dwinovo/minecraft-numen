package com.dwinovo.numen.api.mixin.inject;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * 聊天框流式输出的钥匙:原版聊天行加了就不能改,同伴的流式回复靠
 * "摘掉旧行 → 补一条更长的新行"模拟打字机效果——摘行要碰私有的
 * {@code allMessages},摘完要 {@code refreshTrimmedMessages} 重排版。
 * 新行经 {@code addMessage} 入栏会被原版逐条写进日志,而打字机每长几个字就换一条——
 * 日志因此被同一段话的无数半截刷爆。所以流式行绕开记日志的那一步,直接走公开的 {@code addMessage} 记完日志之后调的
 * 那个私有 {@code addMessage}(这一代"入显示队列 + 入消息表"在它里面);只在定稿时由公开的 {@code addMessage} 正式记一次。
 * 只读写、调私有方法,不改任何原版逻辑。
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("allMessages")
    List<GuiMessage> numen$allMessages();

    /** 这一代的重排版方法名是单数形态。 */
    @Invoker("refreshTrimmedMessage")
    void numen$refreshTrimmedMessages();

    /** 私有的 {@code addMessage(内容, 签名, 加入的刻, 标签, 只重排)}:{@code onlyTrim} 为假时新行放进消息表的 0 位并排进显示。 */
    @Invoker("addMessage")
    void numen$addMessageUnlogged(Component content, MessageSignature signature, int addedTime, GuiMessageTag tag,
                                  boolean onlyTrim);
}
