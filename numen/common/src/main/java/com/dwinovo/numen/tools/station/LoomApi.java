package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.StackLabel;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.sdk.StackRef;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.task.station.StationTaskRecord.Move;
import com.dwinovo.numen.task.station.StationTaskRecord.Step;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code numen.loom}:织布机。往一面旗帜上织一层图案:旗帜、染料(和图案物品)各进各的格,按图案的 id 在此刻能织的图案里找到它
 * (按钮 = 它在那张表里的序号),成品格出旗帜,取走。只按真客户端点得了的:旗帜满了六层,原版界面不给选。
 */
public final class LoomApi {

    private static final String KIND = "loom";
    private static final int[] BANNER = {0};
    private static final int[] DYE = {1};
    private static final int[] PATTERN = {2};
    private static final int RESULT = 3;
    private static final int[] INPUTS = {0, 1, 2};
    /** 一面旗帜上最多几层图案:原版织布机界面到这个数就不给选了。 */
    private static final int MAX_LAYERS = 6;

    private LoomApi() {}

    public static void install(NumenApi numen) {
        numen.api("loom", "A loom: weave a pattern onto a banner with a dye.", LoomApi.class);
    }

    /** 哪台织布机、哪面旗帜、什么染料、什么图案。 */
    public record Weave(@Doc("The loom's cell.") BlockPos loom,
                        @Doc("The banner, minecraft:white_banner: one you carry.") StackRef banner,
                        @Doc("The dye the pattern is woven in, minecraft:red_dye.") StackRef dye,
                        @Doc("The pattern's id, minecraft:stripe_bottom.") ResourceLocation pattern,
                        @Doc("The banner pattern item a pattern is woven from, minecraft:creeper_banner_pattern.")
                        @Omitted("weave a pattern that needs no such item") Optional<StackRef> item) {}

    @Fn("Weave one pattern onto a banner you carry at a loom.")
    @Example("numen.loom.weave({x = 120, y = 64, z = -35}, \"minecraft:white_banner\", \"minecraft:red_dye\", \"stripe_bottom\")")
    @Example("numen.loom.weave({x = 120, y = 64, z = -35}, \"minecraft:white_banner\", \"minecraft:black_dye\", \"creeper\", "
            + "{item = \"minecraft:creeper_banner_pattern\"})")
    @Note("Walks up to the loom when it is out of reach, puts one banner and one dye in (and the pattern item), "
            + "weaves the pattern and takes the banner; the pattern item comes back with it.")
    @Note("Returns the banner as it is now. When the loom does not offer that pattern it fails listing the "
            + "patterns it offers, and what was put in comes back to you.")
    public static Pending<Stations.Product> weave(ServerCall call, Weave args) {
        ResourceLocation pattern = args.pattern();
        if (!call.her().registryAccess().registryOrThrow(Registries.BANNER_PATTERN).containsKey(pattern)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "no banner pattern is called " + pattern, null);
        }
        AtomicReference<Stations.Product> made = new AtomicReference<>();
        List<Step> steps = new ArrayList<>();
        steps.add(Move.put(args.banner().in(call.her().getInventory()), 1, BANNER, "the banner"));
        steps.add(Move.put(args.dye().in(call.her().getInventory()), 1, DYE, "the dye"));
        args.item().ifPresent(item -> steps.add(Move.put(item.in(call.her().getInventory()), 1, PATTERN,
                "the pattern item")));
        steps.add(new Deed((menu, her) -> {
            ItemStack woven = weave((LoomMenu) menu, her, pattern, args.item().isPresent());
            made.set(Stations.product(her, woven));
            return "wove " + pattern.getPath() + ": " + StackLabel.brief(woven, made.get().details());
        }));
        steps.add(Move.take(null, null, new int[] {RESULT}, "the result", false));
        steps.add(Move.take(null, null, INPUTS, null, true));
        return Stations.at(call, args.loom(), LoomMenu.class, KIND, steps, made::get);
    }

    /**
     * 在此刻能织的图案里找到点名的那一个、按下它,交回成品格里的旗帜。
     *
     * @param withItem 她放了图案物品
     * @throws ApiError 旗帜已经满层,或这个图案此刻不在能织的里面:说能织哪些
     */
    private static ItemStack weave(LoomMenu menu, NumenPlayer her, ResourceLocation pattern, boolean withItem) {
        int layers = menu.getSlot(BANNER[0]).getItem()
                .getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers().size();
        if (layers >= MAX_LAYERS) {
            throw new ApiError(ErrorKind.FAILED, "the banner already has " + layers + " patterns, and a loom adds no "
                    + "more to it", null);
        }
        List<Holder<BannerPattern>> offered = menu.getSelectablePatterns();
        List<String> names = new ArrayList<>();
        int n = -1;
        for (int i = 0; i < offered.size(); i++) {
            ResourceLocation id = offered.get(i).unwrapKey().map(key -> key.location()).orElse(null);
            if (pattern.equals(id)) {
                n = i;
            }
            names.add(String.valueOf(id));
        }
        if (n < 0) {
            throw new ApiError(ErrorKind.NOT_FOUND, "the loom does not offer " + pattern + (withItem ? " with that "
                    + "pattern item" : " without a pattern item") + "; it offers " + (names.isEmpty() ? "none"
                    : String.join(", ", names)), null);
        }
        her.packets().menuButton(n);
        ItemStack woven = menu.getSlot(RESULT).getItem();
        if (menu.getSelectedBannerPatternIndex() != n || woven.isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "the loom wove nothing for " + pattern, null);
        }
        return woven.copy();
    }
}
