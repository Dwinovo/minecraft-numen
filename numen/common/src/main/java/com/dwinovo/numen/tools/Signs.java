package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import java.util.ArrayList;
import java.util.List;

/** 告示牌上的字:{@code numen.scan.block} 读出来、{@code numen.use.sign} 写完读回来用的是同一份读法。 */
public final class Signs {

    private Signs() {}

    /** 一块告示牌的字。 */
    @Doc("The text on a sign.")
    public record Text(@Doc("The four front lines, top to bottom.") List<String> front,
                       @Doc("The four back lines.") List<String> back,
                       @Doc("Waxed: its text can no longer be edited.") boolean waxed) {}

    /** 这一格的告示牌;不是告示牌当场说,并指怎么找。 */
    public static SignBlockEntity at(NumenPlayer her, BlockPos pos) {
        if (her.level().getBlockEntity(pos) instanceof SignBlockEntity sign) {
            return sign;
        }
        throw new ApiError(ErrorKind.NOT_FOUND, "there is no sign at " + pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                Call.of("numen.scan.blocks", "#minecraft:all_signs"));
    }

    public static Text read(SignBlockEntity sign) {
        return new Text(lines(sign.getFrontText()), lines(sign.getBackText()), sign.isWaxed());
    }

    /** 一面的四行字。 */
    public static List<String> lines(SignText text) {
        List<String> out = new ArrayList<>();
        for (Component line : text.getMessages(false)) {
            out.add(line.getString());
        }
        return out;
    }
}
