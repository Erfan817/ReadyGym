package com.readygym.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuscleTaxonomyTest {

    // ---------- 归一化 ----------

    @Test fun sameMuscleSpelledDifferentlyCollapsesToOneKey() {
        val shoulders = setOf("shoulders", "deltoids", "delts", "rear deltoids", "rotator cuff")
        assertEquals(setOf(MuscleGroup.SHOULDERS), shoulders.map { MuscleTaxonomy.canonical(it) }.toSet())

        val quads = setOf("quads", "quadriceps", "QUADRICEPS")
        assertEquals(setOf(MuscleGroup.QUADRICEPS), quads.map { MuscleTaxonomy.canonical(it) }.toSet())

        val back = setOf("upper back", "lats", "latissimus dorsi", "back", "rhomboids")
        assertEquals(setOf(MuscleGroup.UPPER_BACK), back.map { MuscleTaxonomy.canonical(it) }.toSet())
    }

    @Test fun undrawableMusclesAreDroppedRatherThanGuessed() {
        for (name in listOf("cardiovascular system", "ankles", "hands", "feet", "sternocleidomastoid", "ankle stabilizers")) {
            assertNull("$name 不应被映射到任何可绘制肌群", MuscleTaxonomy.canonical(name))
        }
    }

    @Test fun unknownAndBlankNamesReturnNull() {
        assertNull(MuscleTaxonomy.canonical(null))
        assertNull(MuscleTaxonomy.canonical(""))
        assertNull(MuscleTaxonomy.canonical("   "))
        assertNull(MuscleTaxonomy.canonical("forehead"))
        assertNull(MuscleTaxonomy.canonical("左肩胛骨内侧"))
    }

    @Test fun canonicalizationNormalizesCaseAndWhitespace() {
        assertEquals(MuscleGroup.CHEST, MuscleTaxonomy.canonical("  Pectorals  "))
        assertEquals(MuscleGroup.ABS, MuscleTaxonomy.canonical("CORE"))
    }

    @Test fun droppedNamesAreReportedInsteadOfSilentlySwallowed() {
        val result = MuscleTaxonomy.canonicalAllWithDropped(
            listOf("chest", "forehead", "triceps", "cardiovascular system", " ", "hips"))
        assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS), result.muscles)
        assertEquals(listOf("forehead", "cardiovascular system", "hips"), result.dropped)
    }

    // ---------- 动作权重 ----------

    @Test fun targetCountsFullAndSecondaryCountsPartially() {
        val weights = MuscleTaxonomy.weightsOf(
            target = "delts",
            secondary = listOf("traps"),
        )
        assertEquals(1.0, weights.getValue(MuscleGroup.SHOULDERS), 1e-9)
        assertEquals(MuscleTaxonomy.SECONDARY_WEIGHT, weights.getValue(MuscleGroup.TRAPEZIUS), 1e-9)
    }

    @Test fun muscleListedAsBothTargetAndSecondaryIsNotDoubleCounted() {
        // 上游数据里 target 与 secondary 重复很常见，重复时必须保留更高的权重。
        val weights = MuscleTaxonomy.weightsOf(target = "lats", secondary = listOf("lats", "biceps"))
        assertEquals(1.0, weights.getValue(MuscleGroup.UPPER_BACK), 1e-9)
        assertEquals(MuscleTaxonomy.SECONDARY_WEIGHT, weights.getValue(MuscleGroup.BICEPS), 1e-9)
        assertEquals(2, weights.size)
    }

    @Test fun unidentifiableMuscleNamesFallBackToBodyPart() {
        val weights = MuscleTaxonomy.weightsOf(target = "forehead", secondary = listOf("eyebrows"), bodyPart = "chest")
        assertEquals(mapOf(MuscleGroup.CHEST to 1.0), weights)
    }

    @Test fun bodyPartFallbackIsNotUsedWhenRealMusclesExist() {
        val weights = MuscleTaxonomy.weightsOf(target = "abs", secondary = emptyList(), bodyPart = "upper legs")
        assertEquals(mapOf(MuscleGroup.ABS to 1.0), weights)
    }

    @Test fun bodyPartWeightsSumToOneRatherThanCountingEachMuscleFully() {
        val weights = MuscleTaxonomy.weightsOf(target = "unknown", secondary = emptyList(), bodyPart = "upper legs")
        assertEquals(1.0, weights.values.sum(), 1e-9)
        assertTrue(weights.keys.all { it in MuscleTaxonomy.DRAWABLE })
    }

    @Test fun nothingRecognisableAndNoBodyPartYieldsEmptyInsteadOfInventing() {
        assertTrue(MuscleTaxonomy.weightsOf("forehead", listOf("eyebrows")).isEmpty())
    }

    @Test fun weightsAreOrderedHeadToToe() {
        val weights = MuscleTaxonomy.weightsOf(
            target = "abs",
            secondary = listOf("delts", "calves", "biceps"),
        )
        val order = weights.keys.map { MuscleTaxonomy.DRAWABLE.indexOf(it) }
        assertEquals(order.sorted(), order)
    }

    // ---------- 训练负荷 ----------

    @Test fun loadIsEffectiveSetsNotKilograms() {
        val squat = MuscleTaxonomy.weightsOf("quads", listOf("glutes"))
        val lateralRaise = MuscleTaxonomy.weightsOf("delts", emptyList())
        val load = MuscleTaxonomy.loadOf(listOf(squat to 3, lateralRaise to 4))
        assertEquals(3.0, load.getValue(MuscleGroup.QUADRICEPS), 1e-9)
        assertEquals(3.0 * MuscleTaxonomy.SECONDARY_WEIGHT, load.getValue(MuscleGroup.GLUTES), 1e-9)
        assertEquals(4.0, load.getValue(MuscleGroup.SHOULDERS), 1e-9)
    }

    @Test fun zeroOrNegativeSetCountsContributeNothing() {
        val chest = MuscleTaxonomy.weightsOf("pectorals", emptyList())
        assertTrue(MuscleTaxonomy.loadOf(listOf(chest to 0, chest to -2)).isEmpty())
    }

    // ---------- 着色与排名 ----------

    @Test fun levelsAreRelativeToTheHardestWorkedMuscleByDefault() {
        val levels = MuscleTaxonomy.levelsOf(mapOf(MuscleGroup.CHEST to 12.0, MuscleGroup.TRICEPS to 3.0))
        assertEquals(4, levels.getValue(MuscleGroup.CHEST))
        assertEquals(1, levels.getValue(MuscleGroup.TRICEPS))
        assertEquals(0, levels.getValue(MuscleGroup.CALVES))
        // 每个可绘制肌群都有档位，UI 不需要处理缺失键
        assertEquals(MuscleTaxonomy.DRAWABLE.size, levels.size)
    }

    @Test fun absoluteThresholdsDoNotRenormalizeToTheStrongestMuscle() {
        val rules = listOf(
            MuscleTaxonomy.LevelRule(at = 0.0, level = 1),
            MuscleTaxonomy.LevelRule(at = 4.0, level = 2),
            MuscleTaxonomy.LevelRule(at = 8.0, level = 3),
            MuscleTaxonomy.LevelRule(at = 12.0, level = 4, exclusive = true),
        )
        val light = MuscleTaxonomy.levelsOf(mapOf(MuscleGroup.CHEST to 5.0), rules)
        assertEquals(2, light.getValue(MuscleGroup.CHEST))
        // 相对归一本来会把 5.0 判成 4；绝对分档必须保持它的语义档位
        assertEquals(2, MuscleTaxonomy.levelsOf(mapOf(MuscleGroup.CHEST to 5.0)).getValue(MuscleGroup.CHEST) - 2)
    }

    @Test fun exclusiveThresholdExcludesTheBoundary() {
        val rules = listOf(MuscleTaxonomy.LevelRule(at = 10.0, level = 4, exclusive = true))
        assertEquals(0, MuscleTaxonomy.levelsOf(mapOf(MuscleGroup.CHEST to 10.0), rules).getValue(MuscleGroup.CHEST))
        assertEquals(4, MuscleTaxonomy.levelsOf(mapOf(MuscleGroup.CHEST to 10.01), rules).getValue(MuscleGroup.CHEST))
    }

    @Test fun rankPutsWorkedMusclesFirstAndUntrainedInBodyOrder() {
        val ranked = MuscleTaxonomy.rankOf(mapOf(MuscleGroup.CALVES to 4.0, MuscleGroup.CHEST to 9.0))
        assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.CALVES), ranked.worked)
        assertEquals(MuscleTaxonomy.DRAWABLE.filter { it !in setOf(MuscleGroup.CHEST, MuscleGroup.CALVES) }, ranked.missed)
    }

    // ---------- 与计划层的一致性 ----------

    @Test fun everyDrawableMuscleIsCoveredByTheAliasTable() {
        // 防止以后往枚举里加了肌群却忘了给别名，导致它永远统计不到。
        for (muscle in MuscleTaxonomy.DRAWABLE) {
            assertEquals("枚举名本身必须能被归一化", muscle, MuscleTaxonomy.canonical(muscle.name))
        }
    }

    @Test fun drawableContainsEachMuscleOnce() {
        assertEquals(MuscleTaxonomy.DRAWABLE.size, MuscleTaxonomy.DRAWABLE.toSet().size)
        assertEquals(MuscleGroup.entries.toSet(), MuscleTaxonomy.DRAWABLE.toSet())
    }
}
