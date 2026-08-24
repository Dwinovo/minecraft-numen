package com.dwinovo.numen.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * 聊天框流式输出的钥匙:原版聊天行加了就不能改,同伴的流式回复靠
 * "摘掉旧行 → 补一条更长的新行"模拟打字机效果——摘行要碰私有的
 * {@code allMessages},摘完要 {@code refreshTrimmedMessages} 重排版。
 * 只读一写,不改任何原版逻辑。
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("allMessages")
    List<GuiMessage> numen$allMessages();

    /** 这一代的重排版方法名是单数形态。 */
    @Invoker("refreshTrimmedMessage")
    void numen$refreshTrimmedMessages();
}
