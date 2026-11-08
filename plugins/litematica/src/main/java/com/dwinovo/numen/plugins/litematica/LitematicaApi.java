package com.dwinovo.numen.plugins.litematica;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;

import java.util.List;
import java.util.Optional;

/**
 * {@code litematica.placement}:主人的 Litematica 里加载着的投影(placement)。三个函数都在主人的客户端上执行——Litematica 是纯客户端模组,
 * 投影只活在那里;函数本身不碰 Litematica 的类,碰它的是 {@link Projections} 与 {@link Preview},专用服务器与没装 Litematica 的客户端上这两个类
 * 不会被加载。
 */
public final class LitematicaApi {

    /** 这个联动在她的 API 里的名字空间。 */
    static final String NAMESPACE = "litematica";

    private LitematicaApi() {}

    static void install(NumenApi numen) {
        numen.api("placement", "Litematica: the schematic projections your owner has loaded, their cells layer by layer, "
                + "and showing your own design to them as a projection.", LitematicaApi.class);
    }

    /** 尺寸:各方向多少格。 */
    @Doc("A size in cells.")
    public record Size(int x, int y, int z) {}

    /** 投影占的世界范围。 */
    @Doc("The world box a projection covers: the box around its enabled sub-regions.")
    public record Bounds(@Doc("Lowest corner, inclusive.") BlockPos min,
                         @Doc("Highest corner, inclusive.") BlockPos max,
                         Size size) {}

    /** 一份投影。 */
    @Doc("One projection your owner has loaded in Litematica.")
    public record Placement(@Doc("What litematica.placement.cells takes as its placement option.") String name,
                            @Doc("The schematic file it was loaded from, e.g. tower.litematic.") Optional<String> file,
                            @Doc("The cell the schematic's own origin lands on.") BlockPos origin,
                            @Doc("None when every sub-region is switched off.") Optional<Bounds> bounds,
                            @Doc("Turned clockwise seen from above, about its origin: 0, 90, 180 or 270.") int rotation,
                            @Doc("none; left_right flips north-south (z), front_back flips west-east (x).") Mirror mirror,
                            @Doc("Switched on in Litematica.") boolean enabled,
                            @Doc("The one litematica.placement.cells reads when no placement is named.") boolean selected,
                            @Doc("Cells with a block in them, air left out, sub-regions switched off left out.") int cells) {}

    /** 主人当前的分层显示。 */
    @Doc("The layer display your owner has set in Litematica: which layers of the projections are drawn.")
    public record Layer(@Doc("Litematica's own names: all, single_layer, layer_range, all_below or all_above.") String mode,
                        @Doc("The axis the layers are along; y is the usual one.") Direction.Axis axis,
                        @Doc("Lowest layer shown.") @Omitted("unbounded below") Optional<Integer> from,
                        @Doc("Highest layer shown.") @Omitted("unbounded above") Optional<Integer> to) {}

    /** 加载着的投影与分层显示。 */
    @Doc("What your owner has loaded in Litematica.")
    public record Loaded(Layer layer, List<Placement> placements) {}

    @Fn("The projections your owner has loaded in Litematica, which one is selected, and the layer display they have set.")
    @Example("litematica.placement.list()")
    @Example("for _, p in ipairs(litematica.placement.list().placements) do print(p.name, p.cells) end")
    @Note("Reads your owner's Litematica on their client and changes nothing. An empty list means nothing is loaded "
            + "(or Litematica has no placements yet).")
    @Note("layer is the display setting, not a limit: litematica.placement.cells reads whichever layers you ask for.")
    @SeeAlso({"litematica.placement.cells", "litematica.placement.preview"})
    public static Loaded list(ClientCall call) {
        return Projections.loaded();
    }

    /** 读哪些层。 */
    public record Read(@Doc("The lowest layer to read: a world y.") int from,
                       @Doc("The highest layer to read: a world y.")
                       @Omitted("only the layer from") @Positional Optional<Integer> to,
                       @Doc("Which projection: its name as litematica.placement.list prints it.")
                       @Omitted("the selected one") Optional<String> placement) {}

    @Fn("The cells of one projection on a layer or a few layers, in world coordinates, as Cells that numen.build.place, "
            + "numen.build.diff take as they are.")
    @Example("litematica.placement.cells(64)")
    @Example("litematica.placement.cells(64, 66)")
    @Example("numen.build.place(litematica.placement.cells(64))")
    @Example("litematica.placement.cells(64, {placement = \"tower\"})")
    @Note("Each cell is a Block: name is the block with its full state, written as /setblock takes it "
            + "(minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]), pos is the world cell. "
            + "Litematica's own placement is applied: origin, sub-regions, rotation and mirror.")
    @Note("Air is not returned. A layer the projection does not reach returns an empty list. Sub-regions switched off in "
            + "Litematica are left out.")
    @Note("At most " + LuaCodecs.MAX_CELLS + " cells come back in one call; asking for more fails with bad_argument and a hint "
            + "that reads the layers that fit. litematica.placement.list says how many cells each projection has.")
    @Note("Reads your owner's Litematica on their client and changes nothing. A name that matches no projection fails with "
            + "not_found.")
    @SeeAlso({"litematica.placement.list", "numen.build.place", "numen.build.diff"})
    public static List<BlockAt> cells(ClientCall call, Read args) {
        return Projections.cells(args.from(), args.to().orElse(args.from()), args.placement().orElse(null));
    }

    /** 预览什么。 */
    public record Show(@Doc("Your design: Cells, each a Block with the block for that cell written as /setblock takes it.")
                       List<BlockAt> cells,
                       @Doc("The projection's name and its file name, without extension: letters, digits, space, _ - . ( ) only.")
                       String name) {}

    @Fn("Show a design to your owner as a Litematica projection, at the cells' own world coordinates.")
    @Example("litematica.placement.preview({{name = \"cobblestone\", pos = {x = 100, y = 64, z = -20}}, "
            + "{name = \"oak_stairs[facing=east]\", pos = {x = 101, y = 64, z = -20}}}, \"my_house\")")
    @Example("litematica.placement.preview(litematica.placement.cells(64, 66), \"layers_64_to_66\")")
    @Note("Changes only your owner's client display, not the world: the cells become a schematic saved as "
            + "<schematics folder>/numen/<name>.litematic, which your owner can load again, edit and share, and it is loaded "
            + "as a placement they see in the world. A projection already made with the same name is replaced, so calling it "
            + "again with a changed design updates what they see. Air cells are not shown.")
    @Note("Returns the projection, as litematica.placement.list prints it.")
    @SeeAlso({"litematica.placement.list", "numen.build.place"})
    public static Placement preview(ClientCall call, Show args) {
        return Preview.show(args.cells(), args.name());
    }
}
