package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 建筑设计守则:动工前的设计知识库。内容存于 {@code config/numen/skills/}
 * 的 markdown(首次运行播种默认守则),玩家/服主可自行增改风格包——工具
 * 只是把文本递给大脑,设计判断留给模型。
 */
public final class BuildGuideTool implements NumenTool {

    /** 默认守则:首次运行写入,之后以磁盘文件为准(用户可改)。 */
    private static final String DEFAULT_GUIDE = """
            # Building Design Guide (consult BEFORE building)

            ## Workflow
            1. PLAN in your head first: purpose, footprint, height, main material + accent material.
            2. Check the site: flat enough? big enough? Use goto/look to inspect before anchoring.
            3. Build big-to-small with ONE build call when possible: shapes for volumes, blocks for details.
            4. After the task finishes, LOOK at the result, compare against the checklist below, and fix
               gaps with a small follow-up build call.

            ## Size reference (width x depth x height)
            - hut / shed: 7 x 7 x 6      - house / shop: 12 x 10 x 8
            - mansion / temple: 18 x 15 x 12      - castle / cathedral: 30 x 25 x 20
            Wall height at least 4 so the interior does not feel cramped; door opening 1 wide x 2 tall.

            ## Composition order (matches how the task builds: bottom-up layers)
            1. foundation slab (box, 1 thick) -> 2. frame pillars (cylinder r=0 or 1x1 boxes at corners)
            -> 3. walls (hollow box) -> 4. roof (stacked shrinking boxes for gable, or hollow sphere top
            half for dome) -> 5. openings (air cells for door/windows) -> 6. details (stairs facing the
            right way, torches, slabs as trim).

            ## Common mistakes checklist
            - Gable triangles under sloped roofs must be FILLED (stack rows shrinking by 1 each layer).
            - Roof should OVERHANG walls by 1 block; seal it — no holes.
            - Windows: cut with air cells THEN add panes/trim; keep them 1-2 above floor level.
            - Door: cut a 1x2 air opening before placing the door blocks (lower + upper half).
            - Interior floor level = anchor y; do not bury the entrance — add a step if terrain is lower.
            - Light the inside (torches) or mobs will spawn.
            - Mix materials: one main + one accent (e.g. stone_bricks + oak_log corners) beats a single
              material box.

            ## Tool mapping
            - Volumes and carving: build with `shapes` (box/line/cylinder/sphere, hollow, air carves).
            - Precise stateful blocks (stairs facing, slabs top/bottom, doors): build with `blocks`.
            - Whole prebuilt files: `blueprint` tool (list first, then build at a flat anchor).
            """;

    @Override
    public String name() {
        return "build_guide";
    }

    @Override
    public String description() {
        return "Read the building design guide (sizes, composition order, quality checklist, tool mapping). "
                + "Consult this BEFORE designing or building anything non-trivial with the build/blueprint "
                + "tools, and again when a finished build looks wrong. Server owners can edit or extend the "
                + "guide files under config/numen/skills/.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of());
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        reply.accept(TaskResult.ok(loadGuide(self.getServer()), Map.of()).toJson());
    }

    /** 聚合 skills 目录下全部 markdown(字典序);目录为空时播种默认守则。 */
    private static String loadGuide(MinecraftServer server) {
        Path dir = server.getServerDirectory().resolve("config").resolve("numen").resolve("skills");
        try {
            Files.createDirectories(dir);
            Path seed = dir.resolve("building_design.md");
            if (!Files.exists(seed)) {
                Files.writeString(seed, DEFAULT_GUIDE);
            }
            StringBuilder out = new StringBuilder();
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                out.append(Files.readString(p)).append("\n\n");
                            } catch (IOException e) {
                                out.append("(unreadable: ").append(p.getFileName()).append(")\n\n");
                            }
                        });
            }
            return out.toString().strip();
        } catch (IOException e) {
            throw new RuntimeException("cannot read skills directory: " + e.getMessage(), e);
        }
    }
}
