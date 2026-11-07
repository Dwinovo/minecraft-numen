package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.api.sdk.BadValue;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Codec;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.script.ScriptType;

/**
 * {@code numen.gui.choose} 的一项:界面里选项的编号(从 1 起,和 {@code numen.gui.view().screen} 列的一样),或一个名字(信标的效果)。
 * 哪一种得看是哪个界面:它自己要的那一种之外给了另一种,当场说它要什么。
 *
 * @param number 编号;给的是名字时为 null
 * @param name   名字;给的是编号时为 null
 */
public record Pick(Integer number, String name) {

    public Pick {
        if ((number == null) == (name == null)) {
            throw new IllegalArgumentException("a number or a name, exactly one of them");
        }
    }

    public static final Codec<Pick> CODEC = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.union(ScriptType.INTEGER, ScriptType.STRING);
        }

        @Override
        public Pick decode(Object value) {
            if (value instanceof String s) {
                return new Pick(null, s);
            }
            if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                    && Math.abs(n.doubleValue()) < Integer.MAX_VALUE) {
                return new Pick(n.intValue(), null);
            }
            throw new BadValue("expected a whole number or a name; got " + BadValue.given(value));
        }

        @Override
        public Object encode(Pick value) {
            return value.number != null ? (Object) (long) value.number : value.name;
        }
    };

    /**
     * 给的得是 1 到 {@code count} 的编号;不是编号、超出范围当场拒绝,说清有几项。
     *
     * @param what  这些选项叫什么,如 {@code recipe}
     * @param count 此刻有几项
     */
    public int numberIn(String what, int count) {
        if (number == null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "this window takes the number of a " + what + " (1 to "
                    + Math.max(count, 1) + "), not the name \"" + name + "\"", Call.of("numen.gui.view"));
        }
        if (number < 1 || number > count) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, what + " " + number + " does not exist: this window has "
                    + (count == 0 ? "none right now" : "1 to " + count), Call.of("numen.gui.view"));
        }
        return number;
    }
}
