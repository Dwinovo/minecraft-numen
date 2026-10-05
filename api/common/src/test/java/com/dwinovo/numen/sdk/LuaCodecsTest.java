package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptEngine;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 值转换往返:写成脚本的值再读回来是同一个;读不成的说要什么、给了什么。脚本的值是 {@code null}、布尔、{@code Long}、
 * {@code Double}、字符串、列表、名字到值的表。
 */
class LuaCodecsTest {

    public enum Mood { CALM, ANGRY_NOW }

    public record Inner(@Doc("A count.") int count) {}

    public record Thing(String name, Optional<Integer> heavyWeight, Mood mood, List<Inner> parts,
                        Map<String, Long> tally, BlockPos at) {}

    @SuppressWarnings("unchecked")
    private static <T> Codec<T> codec(Class<T> type) {
        return (Codec<T>) LuaCodecs.of(type, SdkFixture.NAMESPACE);
    }

    @Test
    void aRecordIsATableWithSnakeCaseFieldsAndAMissingOptionalIsLeftOut() {
        Thing thing = new Thing("box", Optional.empty(), Mood.ANGRY_NOW, List.of(new Inner(2)),
                Map.of("a", 1L), new BlockPos(1, 64, -3));
        Object lua = codec(Thing.class).encode(thing);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "box");
        expected.put("mood", "angry_now");
        expected.put("parts", List.of(Map.of("count", 2L)));
        expected.put("tally", Map.of("a", 1L));
        expected.put("at", Map.of("x", 1L, "y", 64L, "z", -3L));
        assertEquals(expected, lua);
        assertEquals(thing, codec(Thing.class).decode(lua));

        Thing heavy = new Thing("box", Optional.of(7), Mood.CALM, List.of(), Map.of(), BlockPos.ZERO);
        assertEquals(heavy, codec(Thing.class).decode(codec(Thing.class).encode(heavy)));
    }

    @Test
    void aRecordDeclaresItsClassUnderTheNamespace() {
        assertEquals("gt.Thing", ScriptEngine.IN_USE.typeText(codec(Thing.class).type()));
        assertTrue(LuaCodecs.classNamed("gt.Thing") != null, "the class is declared");
    }

    @Test
    void aValueThatDoesNotReadSaysWhatItWantsAndWhere() {
        Map<String, Object> wrong = new LinkedHashMap<>();
        wrong.put("name", "box");
        wrong.put("mood", "sleepy");
        wrong.put("parts", List.of());
        wrong.put("tally", Map.of());
        wrong.put("at", Map.of("x", 1L, "y", 2L, "z", 3L));
        BadValue bad = assertThrows(BadValue.class, () -> codec(Thing.class).decode(wrong));
        assertTrue(bad.getMessage().contains("expected one of calm, angry_now"), bad.getMessage());
        assertTrue(bad.getMessage().contains("mood"), "says which field: " + bad.getMessage());
    }

    @Test
    void aTableWithNoOrderOfItsOwnIsWrittenWithItsKeysSorted() {
        assertEquals(List.of("arrive", "range", "x"),
                List.copyOf(((Map<?, ?>) LuaCodecs.encode(Map.of("x", 1L, "range", 2L, "arrive", "near"))).keySet()));
        Map<String, Object> ordered = new java.util.LinkedHashMap<>();
        ordered.put("z", 1L);
        ordered.put("a", 2L);
        assertEquals(List.of("z", "a"), List.copyOf(((Map<?, ?>) LuaCodecs.encode(ordered)).keySet()));
    }

    @Test
    void aCellReadsDecimalsAsTheCellTheyAreInAndAnOldStyleCellSaysHowToWriteIt() {
        Codec<BlockPos> cell = codec(BlockPos.class);
        assertEquals(new BlockPos(1, 64, -3), cell.decode(Map.of("x", 1.5, "y", 64L, "z", -2.2)));
        assertEquals(new BlockPos(5, 6, 7), cell.decode(Map.of("name", "stone", "pos", Map.of("x", 5L, "y", 6L, "z", 7L))));
        BadValue old = assertThrows(BadValue.class, () -> cell.decode(List.of(1L, 2L, 3L)));
        assertEquals(Map.of("x", 1L, "y", 2L, "z", 3L), old.instead());
    }

    @Test
    void anEntityIsItsIdOrItsUuid() {
        Codec<EntityRef> entity = codec(EntityRef.class);
        assertEquals(EntityRef.id(184), entity.decode(184L));
        assertEquals(EntityRef.id(184), entity.decode(Map.of("id", 184L, "type", "minecraft:pig")));
        UUID uuid = UUID.randomUUID();
        assertEquals(new EntityRef(null, uuid), entity.decode(uuid.toString()));
        assertEquals(uuid.toString(), entity.encode(new EntityRef(null, uuid)));
        assertEquals(184L, entity.encode(EntityRef.id(184)));
    }

    public sealed interface Shape permits Square, Circle {}

    public record Square(int side) implements Shape {}

    public record Circle(double radius) implements Shape {}

    @Test
    void oneOfSeveralIsWrittenByItsKindAndReadAsTheFirstThatFits() {
        Codec<Shape> shape = codec(Shape.class);
        assertEquals(Map.of("side", 2L), shape.encode(new Square(2)));
        assertEquals(new Circle(1.5), shape.decode(Map.of("radius", 1.5)));
        assertEquals("gt.Square|gt.Circle", ScriptEngine.IN_USE.typeText(shape.type()));
    }

    @Test
    void aLiteralIsTheValueWrittenAsTheScriptWritesIt() {
        assertEquals("{x = 1, y = 64, z = -3}", LuaCodecs.literal(new BlockPos(1, 64, -3)));
        assertEquals("\"calm\"", LuaCodecs.literal(Mood.CALM));
    }
}
