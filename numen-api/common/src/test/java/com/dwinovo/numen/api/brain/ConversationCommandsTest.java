package com.dwinovo.numen.api.brain;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 补全读"他写的会话":指令树直接挂上来(原版)与经重定向挂上来(Paper 把插件指令登记成别名)都读得到。 */
class ConversationCommandsTest {

    /** 补全时 Brigadier 交给参数补全器的上下文:整行解析后,按光标前的输入建出来的根上下文。 */
    private static CommandContext<CommandSourceStack> suggestionContext(CommandDispatcher<CommandSourceStack> dispatcher,
                                                                        String input) {
        return dispatcher.parse(input, null).getContext().build(input);
    }

    @Test
    void theConversationIsReadFromATreeMountedDirectly() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("numen").then(ConversationCommands.chat()));

        assertEquals("Duo", ConversationCommands.conversationArgument(suggestionContext(dispatcher, "numen chat Duo @")));
    }

    @Test
    void theConversationIsReadFromATreeMountedThroughARedirect() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        LiteralCommandNode<CommandSourceStack> real = dispatcher.register(
                LiteralArgumentBuilder.<CommandSourceStack>literal("real").then(ConversationCommands.chat()));
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("numen").redirect(real));

        assertEquals("Duo", ConversationCommands.conversationArgument(suggestionContext(dispatcher, "numen chat Duo @")));
    }
}
