package com.dwinovo.numen.api.entity;

import java.util.Map;

import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.describe.AnswerText;
import com.dwinovo.numen.api.describe.Descriptions;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * 一叠东西在模型眼里是什么样:物品 id 一样、组件不一样的几叠(装着钻石的潜影盒与空盒、不同的药水、附魔与没附魔的镐)靠 id 后面那一段
 * 分开。<b>一叠东西写成这句短话只有这一处</b>:每一轮的背包清单({@code runtime_state})、回执里"用了哪一叠"都读它,所以模型在清单里
 * 看到的写法与回执里报的是同一个。
 *
 * <p>这里不认识任何一种物品:一叠有什么不一样,问登记在 {@link Questions#ITEM} 下的回答者({@link #details}),他们说的字段照
 * {@link AnswerText} 写在 id 后面。没人说话的一叠只有 id;两种不一样的叠没人说得出区别时写出来一样。回答者在服务端,所以这句话
 * 只在服务端主线程上写。
 */
public final class StackLabel {

    private StackLabel() {}

    /** 关于这一叠,回答者们说的:回答者的名字到它说的字段;没人说话是空表。 */
    public static Map<String, Map<String, Object>> details(NumenPlayer her, ItemStack stack) {
        return Descriptions.of(Questions.ITEM, her, stack);
    }

    /** 物品 id,回答者有话说时后接他们说的:{@code minecraft:shulker_box[contents={minecraft:diamond=3}]}。 */
    public static String of(NumenPlayer her, ItemStack stack) {
        return AnswerText.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), details(her, stack));
    }

    /** 不带命名空间的 {@link #of}:回执正文里用,{@code shulker_box[contents={minecraft:diamond=3}]}。 */
    public static String brief(NumenPlayer her, ItemStack stack) {
        return brief(stack, details(her, stack));
    }

    /** 同 {@link #brief(NumenPlayer, ItemStack)},回答由调用方给(已经问过 {@link #details} 的)。 */
    public static String brief(ItemStack stack, Map<String, Map<String, Object>> details) {
        return AnswerText.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), details);
    }
}
