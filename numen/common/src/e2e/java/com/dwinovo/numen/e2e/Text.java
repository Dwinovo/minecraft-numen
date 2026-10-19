package com.dwinovo.numen.e2e;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 把服务端发来的聊天组件读成场景能断言的东西。原版客户端收到可翻译组件时按自己的语言表翻,这里没有语言表,
 * 所以可翻译组件读成 {@code 键[参数, …]}——服务端已经译好的(Numen 自己的键)读出来是正文,没译的原版键保留键名与参数,
 * 场景可以断言"这里不该再有 numen 的键"。
 */
final class Text {

    private Text() {}

    /** 组件的文字,子组件依次拼上。 */
    static String plain(Component component) {
        StringBuilder out = new StringBuilder();
        append(component, out);
        return out.toString();
    }

    private static void append(Component component, StringBuilder out) {
        if (component instanceof TextComponent text) {
            out.append(text.content());
        } else if (component instanceof TranslatableComponent translatable) {
            out.append(translatable.key());
            List<String> args = new ArrayList<>();
            translatable.arguments().forEach(arg -> args.add(plain(arg.asComponent())));
            out.append(args);
        }
        component.children().forEach(child -> append(child, out));
    }

    /** 根上的翻译键;根不是可翻译组件是 {@code null}。 */
    static String key(Component component) {
        return component instanceof TranslatableComponent translatable ? translatable.key() : null;
    }

    /** 根上的翻译参数,各读成文字。 */
    static List<String> arguments(Component component) {
        List<String> out = new ArrayList<>();
        if (component instanceof TranslatableComponent translatable) {
            translatable.arguments().forEach(arg -> out.add(plain(arg.asComponent())));
        }
        return out;
    }

    /** 消息里依次出现的、点下去会敲的命令(含斜杠,和客户端点击时的值一样)。 */
    static List<String> clickCommands(Component component) {
        List<String> out = new ArrayList<>();
        collect(component, out);
        return out;
    }

    private static void collect(Component component, List<String> out) {
        ClickEvent click = component.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) {
            out.add(click.value());
        }
        component.children().forEach(child -> collect(child, out));
    }
}
