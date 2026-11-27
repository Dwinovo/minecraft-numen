package com.dwinovo.numen.plugins.minecraft.tools.shape;

import com.dwinovo.numen.plugins.minecraft.Constants;
import com.dwinovo.numen.script.ScriptType;

import java.util.List;

/**
 * 形状函数的两种值:一串格(Cells)与一块选区(Region)。它们的方法写在本插件的 Lua 模块 {@code minecraft.shape} 里(形状是游戏里的内容,
 * 不是引擎的),类经 {@code numen.codec} 声明({@link com.dwinovo.numen.plugins.minecraft.build.Design#CODEC})。
 */
public final class ShapeTypes {

    /** 方法住的模块。 */
    private static final String HOME = Constants.PLUGIN + ".shape";

    private ShapeTypes() {}

    /**
     * 一串格:每一格是一个 Block(那一格要放的方块)或一个 Pos(只是那一格)。它可以带一个原点(形状函数画在哪、粘贴落在哪;转向、翻转、
     * 粘贴围着它),原点不在这串格的数据里,在值自己带的元表上。它也收 Region 的每个方法,当作正好有这些格的任意形状的选区。
     */
    public static final ScriptType.Class CELLS = ScriptType.Class.listOf("Cells",
            "A list of cells, each a Block (the block for that cell, written as /setblock takes it) or a Pos (just the "
                    + "cell). The shape functions draw them. Every Region "
                    + "method works on Cells too (cells:set(\"stone\"), cells:faces(\"glass\")), taking them as the "
                    + "selection of exactly these cells.",
            ScriptType.union(new ScriptType.Named("Block"), new ScriptType.Named("Pos"))).methodsIn(HOME);

    /** 一块带形状的选区:记着自己的形状参数,所以能照形状放大缩小。 */
    public static final ScriptType.Class REGION = new ScriptType.Class("Region",
            "A selection that knows its shape (WorldEdit's regions): the shape functions (cuboid, cyl, sphere, ellipsoid, poly and "
                    + "convex) make one when called without a pattern. :set, :walls, :faces and "
                    + ":center draw it as Cells, :expand, :contract, :shift, :outset and :inset change it by its shape "
                    + "(each returns the changed Region), :cells lists its cells.",
            null, List.of(ScriptType.field("kind", ScriptType.choice(List.of("cuboid", "cyl", "ellipsoid", "poly",
                    "convex", "cells")), "What shape it is; cells is any shape given by its cells only, which cannot be "
                    + "expanded or contracted."))).methodsIn(HOME);
}
