package com.dwinovo.numen.describe;

import com.dwinovo.numen.api.Focus;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.describe.Describer;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.tools.Signs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 原版里"这是什么"的回答者,一个只说一样:告示牌上的字、一格方块里存着的物品流体能量、熔炉烧到哪了。经插件的门登记({@link #install}),和模组的
 * 回答者同一个写法;多认一样就是在这里多写一个、列进 {@link #install}。方块本身的事(id、状态、硬度)不在这里,那是
 * {@code numen.scan.block} 自己读的。
 */
public final class VanillaDescribers {

    private VanillaDescribers() {}

    public static void install(NumenApi numen) {
        for (Describer describer : List.<Describer>of(VanillaDescribers::sign, VanillaDescribers::storage,
                VanillaDescribers::furnace)) {
            numen.answer(Questions.DESCRIBE, describer);
        }
    }

    /** 告示牌:两面的字,涂了蜡再说一句。 */
    private static List<String> sign(NumenPlayer her, Focus target) {
        if (target.block() == null || !(her.level().getBlockEntity(target.block()) instanceof SignBlockEntity sign)) {
            return List.of();
        }
        return signLines(Signs.read(sign));
    }

    /** 一块告示牌的字写成的几行:每面四行都写出来(空行是 {@code ""}),她要改哪一行数得出来。 */
    static List<String> signLines(Signs.Text text) {
        List<String> out = new ArrayList<>();
        out.add("sign front: " + quoted(text.front()));
        out.add("sign back: " + quoted(text.back()));
        if (text.waxed()) {
            out.add("sign is waxed: its text can no longer be edited");
        }
        return out;
    }

    private static String quoted(List<String> lines) {
        return lines.stream().map(line -> '"' + line + '"').collect(Collectors.joining(", "));
    }

    /**
     * 一格方块里存着什么:经加载器的标准存储读,不开界面(箱子、熔炉、大多数模组的机器储罐电池)。不露出标准存储的方块是空表;
     * 存储网络的终端只露出它自己那一小块。字怎么写在 {@code BlockStorageReading.lines()} 一处。
     */
    private static List<String> storage(NumenPlayer her, Focus target) {
        return target.block() == null ? List.of() : Services.caps().read(her.level(), target.block()).lines();
    }

    /** 一秒几刻。 */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * 熔炉、高炉、烟熏炉:燃料还烧多久、正在烧的那一件烧到哪了。这三个数方块实体不对外给,读它自己存盘的那份数据
     * ({@code BurnTime}、{@code CookTime}、{@code CookTimeTotal},单位都是刻)。
     */
    private static List<String> furnace(NumenPlayer her, Focus target) {
        if (target.block() == null
                || !(her.level().getBlockEntity(target.block()) instanceof AbstractFurnaceBlockEntity furnace)) {
            return List.of();
        }
        CompoundTag saved = furnace.saveWithoutMetadata(her.level().registryAccess());
        return furnaceLines(saved.getInt("BurnTime"), saved.getInt("CookTime"), saved.getInt("CookTimeTotal"));
    }

    /**
     * 一座炉的进度写成的几行,是零的不写:燃料还烧几秒;正在烧的那一件烧了百分之几,火还着就说还要几秒,火灭了就说它停着
     * (没有火时原版让进度往回退,"还要几秒"不成立)。秒数往上取整。
     *
     * @param burnTicks  这份燃料还烧几刻
     * @param cookTicks  正在烧的那一件已经烧了几刻
     * @param totalTicks 烧好它一共要几刻
     */
    static List<String> furnaceLines(int burnTicks, int cookTicks, int totalTicks) {
        List<String> out = new ArrayList<>();
        if (burnTicks > 0) {
            out.add("burning: the fuel being burned lasts " + seconds(burnTicks) + " s more");
        }
        if (cookTicks > 0 && totalTicks > 0) {
            String done = "cooking: " + Math.min(100, cookTicks * 100 / totalTicks) + "% done, ";
            out.add(burnTicks > 0 ? done + seconds(Math.max(0, totalTicks - cookTicks)) + " s left"
                    : done + "paused: no fuel is burning");
        }
        return out;
    }

    private static int seconds(int ticks) {
        return (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    }
}
