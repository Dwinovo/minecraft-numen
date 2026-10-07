package com.dwinovo.numen.core.tools.perception;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code numen.status}:她此刻的身体、主人、世界。三个函数都在服务端当场读、当场回,不占身体、不动世界;位置是 Pos,原样就能交给要一格的
 * 参数({@code numen.move.to(numen.status.owner().pos)})。
 */
public final class StatusApi {

    private StatusApi() {}

    public static void install(NumenApi numen) {
        numen.api("status", "Your body, your owner and the world right now.", StatusApi.class);
    }

    /** 手里拿着的一样。 */
    public record Held(String item, int count) {}

    /** 两只手。 */
    public record Hands(Optional<Held> mainhand, Optional<Held> offhand) {}

    /** 背包占了几格。 */
    public record Slots(int used, int total) {}

    /** 她的身体。 */
    @Doc("Your body right now.")
    public record Self(@Doc("Your own entity id.") int id,
                       String name,
                       @Doc("survival, creative, adventure or spectator.") String gameMode,
                       double hp,
                       double maxHp,
                       @Doc("0-20.") int hunger,
                       double saturation,
                       @Doc("Where you are (decimals).") Vec3 pos,
                       String dimension,
                       String biome,
                       @Doc("Structures you stand in.") List<String> structures,
                       @Doc("What is in your hands.") Hands hands,
                       Slots backpackSlots,
                       boolean onGround,
                       boolean inWater,
                       @Doc("Breath left, in ticks.") int air,
                       int maxAir,
                       boolean inLava,
                       @Doc("What you wear and what mods report about your body.") Optional<String> bodyState) {}

    @Fn("Your body: health, hunger, position, biome, what is in your hands and on you, movement state.")
    @Example("local me = numen.status.self()\nprint(me.pos.x, me.pos.y, me.pos.z, me.hp)")
    @Note("Instant and read-only: name, game mode, health, hunger and saturation, position, dimension, biome, the "
            + "structures you stand in, what is in your hands, what you wear and what mods report about your body, "
            + "movement state.")
    @Note("It does not list your backpack: what you carry is in front of you every turn; `numen.gui.view()` shows "
            + "exact slots.")
    @SeeAlso({"numen.status.owner", "numen.status.world"})
    public static Self self(ServerCall call) {
        NumenPlayer self = call.her();
        List<String> structures = new ArrayList<>();
        if (self.level() instanceof ServerLevel sl) {
            Registry<Structure> reg = sl.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Structure s : sl.structureManager().getAllStructuresAt(self.blockPosition()).keySet()) {
                ResourceLocation key = reg.getKey(s);
                if (key != null) {
                    structures.add(key.toString());
                }
            }
        }
        // 背包不在这里。它是「状态」不是「事件」——值会沉进对话历史,而历史里的状态永远不会过期:十轮之后她读到那份快照,
        // 和这一轮挂在请求里的实时背包对不上。全量背包只有一个来源(runtime_state 的 <inventory>);要精确到槽位就用 gui view。
        var inv = self.getInventory();
        int used = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) {
                used++;
            }
        }
        // 身体状态片段:<worn>(穿戴位置,原版与模组同一份)打头,其后是插件从身体上读的片段;与挂进 runtime_state 的是同一个汇总
        String bodyState = NumenPlugins.bodyStateFragments(self);
        return new Self(self.getId(), self.getName().getString(), WorkProfile.mode(self).getName(), self.getHealth(),
                self.getMaxHealth(), self.getFoodData().getFoodLevel(), self.getFoodData().getSaturationLevel(),
                self.position(), self.level().dimension().location().toString(),
                self.level().getBiome(self.blockPosition()).unwrapKey().map(k -> k.location().toString())
                        .orElse("unknown"),
                structures, new Hands(held(self.getMainHandItem()), held(self.getOffhandItem())),
                new Slots(used, inv.getContainerSize()), self.onGround(), self.isInWater(),
                // 剩下的气:少了它,身体曾在冰海里淹死而脑子还在从容地规划一趟 870 格的路(2026-07-15)
                self.getAirSupply(), self.getMaxAirSupply(), self.isInLava(),
                bodyState.isEmpty() ? Optional.empty() : Optional.of(bodyState));
    }

    private static Optional<Held> held(ItemStack stack) {
        return stack.isEmpty() ? Optional.empty()
                : Optional.of(new Held(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
    }

    /** 她的主人。 */
    @Doc("Your owner right now; when they are offline, only online (false) is there.")
    public record Owner(boolean online,
                        @Doc("Their entity id.") Optional<Integer> id,
                        Optional<String> name,
                        @Doc("Where they are (decimals), in their dimension's coordinates.") Optional<Vec3> pos,
                        @Doc("Blocks from you, in the same dimension.") Optional<Double> distance,
                        Optional<Boolean> sameDimension,
                        Optional<String> dimension,
                        Optional<Double> hp,
                        Optional<Double> maxHp,
                        Optional<Integer> hunger,
                        Optional<Double> saturation,
                        Optional<String> mainHand,
                        Optional<String> offHand) {}

    @Fn("Your owner: online or not, health, hunger, position, distance from you, held items.")
    @Example("local owner = numen.status.owner()\nif owner.online then print(owner.pos.x, owner.pos.z) end")
    @Note("Instant and read-only. An offline owner comes back as online:false.")
    @SeeAlso("numen.status.self")
    public static Owner owner(ServerCall call) {
        NumenPlayer self = call.her();
        // 全服去找:原版 getOwner() 只在宠物所在的那一层世界里找,别的维度的主人会被当成不在线
        Player player = self.getOwnerUuid() == null ? null : self.resolveOwnerPlayer();
        if (player == null) {
            return new Owner(false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        boolean sameDimension = self.level().dimension().equals(player.level().dimension());
        return new Owner(true, Optional.of(player.getId()), Optional.of(player.getName().getString()),
                Optional.of(player.position()),
                sameDimension ? Optional.of(Math.round(self.distanceTo(player) * 10.0) / 10.0) : Optional.empty(),
                Optional.of(sameDimension), Optional.of(player.level().dimension().location().toString()),
                Optional.of((double) player.getHealth()), Optional.of((double) player.getMaxHealth()),
                Optional.of(player.getFoodData().getFoodLevel()),
                Optional.of((double) player.getFoodData().getSaturationLevel()),
                Optional.of(itemKey(player.getMainHandItem())), Optional.of(itemKey(player.getOffhandItem())));
    }

    private static String itemKey(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 天气。 */
    public enum Weather { CLEAR, RAIN, THUNDER }

    /** 世界。 */
    public record World(String dimension, long gameTime, boolean isBrightOutside, boolean isDarkOutside,
                        Weather weather) {}

    @Fn("The world: dimension, game time, whether it is bright or dark outside, weather.")
    @Example("numen.status.world()")
    @Note("Instant and read-only. Darkness and weather matter for mobs, combat and sailing.")
    @SeeAlso("numen.status.self")
    public static World world(ServerCall call) {
        Level level = call.her().level();
        Weather weather = level.isThundering() ? Weather.THUNDER : level.isRaining() ? Weather.RAIN : Weather.CLEAR;
        return new World(level.dimension().location().toString(), level.getLevelData().getGameTime(), level.isDay(),
                level.isNight(), weather);
    }
}
