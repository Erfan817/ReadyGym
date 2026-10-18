package com.readygym.core

/**
 * 肌群归一化：把动作数据里五花八门的肌群拼写收敛到「人体图能画出来的同一批肌群」。
 *
 * 为什么需要这一层：上游数据集用自由文本命名肌群，"shoulders"/"deltoids"/"delts" 是同一块，
 * "quads"/"quadriceps" 是同一块，"lats"/"latissimus dorsi"/"upper back" 是同一块。不归一化，
 * 同一块肌肉在统计里会被拆成三四个互不相干的键，肌群图、覆盖统计和「哪块没练到」全部失真。
 *
 * 权重规则（与 [MuscleTaxonomy.SECONDARY_WEIGHT] 一致）：
 * - 主动肌（target）计 1.0
 * - 辅助肌（secondary_muscles 里的其余项）计 0.4
 * - 同一块肌肉被多处命中时取最大值，不累加（避免重复计数）
 *
 * 归一化不认识的名字一律丢弃，不猜测、不映射到最近的肌群。丢弃的条目会通过
 * [canonical] 返回 null 暴露给调用方，便于在导入报告中统计而不是静默吞掉。
 *
 * 注意：这是纯规则、纯数据，不含任何训练建议。肌群判定不等于动作难度、安全性或适用性评估。
 */
object MuscleTaxonomy {

    /** 人体图上可以独立着色的肌群，按「从头到脚」顺序排列，也是任何肌群列表的展示顺序。 */
    val DRAWABLE: List<MuscleGroup> = listOf(
        MuscleGroup.TRAPEZIUS, MuscleGroup.SHOULDERS, MuscleGroup.CHEST, MuscleGroup.UPPER_BACK,
        MuscleGroup.SERRATUS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.FOREARMS,
        MuscleGroup.ABS, MuscleGroup.OBLIQUES, MuscleGroup.LOWER_BACK, MuscleGroup.GLUTES,
        MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.ADDUCTORS,
        MuscleGroup.HIP_FLEXORS, MuscleGroup.CALVES, MuscleGroup.TIBIALIS,
    )

    /** 辅助肌相对主动肌的计分权重。 */
    const val SECONDARY_WEIGHT: Double = 0.4

    /**
     * 上游数据出现过的全部肌群拼写。null 表示「不可绘制」，显式丢弃而不是猜。
     * 这份表来自对 1,324 条上游记录的 target / secondary_muscles 全量取值统计。
     */
    private val ALIAS: Map<String, MuscleGroup?> = buildMap {
        // 主动肌常见写法
        put("abs", MuscleGroup.ABS)
        put("abdominals", MuscleGroup.ABS)
        put("core", MuscleGroup.ABS)
        put("lower abs", MuscleGroup.ABS)
        put("pectorals", MuscleGroup.CHEST)
        put("chest", MuscleGroup.CHEST)
        put("upper chest", MuscleGroup.CHEST)
        put("biceps", MuscleGroup.BICEPS)
        put("brachialis", MuscleGroup.BICEPS)
        put("glutes", MuscleGroup.GLUTES)
        put("gluteal", MuscleGroup.GLUTES)
        put("abductors", MuscleGroup.GLUTES)
        put("delts", MuscleGroup.SHOULDERS)
        put("deltoids", MuscleGroup.SHOULDERS)
        put("shoulders", MuscleGroup.SHOULDERS)
        put("rear deltoids", MuscleGroup.SHOULDERS)
        put("rotator cuff", MuscleGroup.SHOULDERS)
        put("triceps", MuscleGroup.TRICEPS)
        put("upper back", MuscleGroup.UPPER_BACK)
        put("back", MuscleGroup.UPPER_BACK)
        put("lats", MuscleGroup.UPPER_BACK)
        put("latissimus dorsi", MuscleGroup.UPPER_BACK)
        put("rhomboids", MuscleGroup.UPPER_BACK)
        put("calves", MuscleGroup.CALVES)
        put("soleus", MuscleGroup.CALVES)
        put("quads", MuscleGroup.QUADRICEPS)
        put("quadriceps", MuscleGroup.QUADRICEPS)
        put("forearms", MuscleGroup.FOREARMS)
        put("forearm", MuscleGroup.FOREARMS)
        put("wrists", MuscleGroup.FOREARMS)
        put("wrist flexors", MuscleGroup.FOREARMS)
        put("wrist extensors", MuscleGroup.FOREARMS)
        put("grip muscles", MuscleGroup.FOREARMS)
        put("hamstrings", MuscleGroup.HAMSTRINGS)
        put("spine", MuscleGroup.LOWER_BACK)
        put("lower back", MuscleGroup.LOWER_BACK)
        put("traps", MuscleGroup.TRAPEZIUS)
        put("trapezius", MuscleGroup.TRAPEZIUS)
        put("levator scapulae", MuscleGroup.TRAPEZIUS)
        put("adductors", MuscleGroup.ADDUCTORS)
        put("groin", MuscleGroup.ADDUCTORS)
        put("inner thighs", MuscleGroup.ADDUCTORS)
        put("serratus anterior", MuscleGroup.SERRATUS)
        put("hip flexors", MuscleGroup.HIP_FLEXORS)
        put("obliques", MuscleGroup.OBLIQUES)
        put("shins", MuscleGroup.TIBIALIS)
        // 明确不可绘制：画不出来就不要假装画得出来
        put("cardiovascular system", null)
        put("ankles", null)
        put("ankle stabilizers", null)
        put("feet", null)
        put("hands", null)
        put("sternocleidomastoid", null)
    }

    /**
     * 自定义动作只有身体部位、没有细肌群时的退路。组内权重之和为 1，
     * 表示「这一部位分摊到这几块肌肉」，而不是每块都记满 1。
     */
    private val BY_BODY_PART: Map<String, Map<MuscleGroup, Double>> = mapOf(
        "chest" to mapOf(MuscleGroup.CHEST to 1.0),
        "back" to mapOf(MuscleGroup.UPPER_BACK to 0.75, MuscleGroup.LOWER_BACK to 0.25),
        "shoulders" to mapOf(MuscleGroup.SHOULDERS to 1.0),
        "upper arms" to mapOf(MuscleGroup.BICEPS to 0.5, MuscleGroup.TRICEPS to 0.5),
        "lower arms" to mapOf(MuscleGroup.FOREARMS to 1.0),
        "waist" to mapOf(MuscleGroup.ABS to 0.7, MuscleGroup.OBLIQUES to 0.3),
        "upper legs" to mapOf(MuscleGroup.QUADRICEPS to 0.4, MuscleGroup.HAMSTRINGS to 0.35, MuscleGroup.GLUTES to 0.25),
        "lower legs" to mapOf(MuscleGroup.CALVES to 0.8, MuscleGroup.TIBIALIS to 0.2),
        "neck" to mapOf(MuscleGroup.TRAPEZIUS to 1.0),
        "full body" to mapOf(
            MuscleGroup.CHEST to 0.2, MuscleGroup.UPPER_BACK to 0.2, MuscleGroup.GLUTES to 0.2,
            MuscleGroup.QUADRICEPS to 0.2, MuscleGroup.HAMSTRINGS to 0.1, MuscleGroup.ABS to 0.1,
        ),
        "cardio" to emptyMap(),
    )

    /**
     * 把一个自由文本肌群名归一化。
     *
     * @return 可绘制的肌群；名字不可识别或明确不可绘制时返回 null。
     */
    fun canonical(raw: String?): MuscleGroup? {
        val key = raw?.trim()?.lowercase() ?: return null
        if (key.isEmpty()) return null
        ALIAS[key]?.let { return it }
        // 允许调用方直接传枚举名（"QUADRICEPS"），方便自编动作与上游数据共用一条路径。
        return DRAWABLE.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }

    /** 一组自由文本肌群 → 去重后的可绘制肌群，保持 [DRAWABLE] 的顺序。 */
    fun canonicalAll(raw: Iterable<String?>): List<MuscleGroup> {
        val seen = LinkedHashSet<MuscleGroup>()
        for (name in raw) canonical(name)?.let { seen += it }
        return DRAWABLE.filter { it in seen }
    }

    /** [canonicalAll] 的丢弃统计版本：返回可绘制肌群与未能识别的原始名字。 */
    fun canonicalAllWithDropped(raw: Iterable<String?>): Canonicalization {
        val kept = LinkedHashSet<MuscleGroup>()
        val dropped = mutableListOf<String>()
        for (name in raw) {
            val slug = canonical(name)
            if (slug != null) kept += slug else if (!name.isNullOrBlank()) dropped += name.trim()
        }
        return Canonicalization(DRAWABLE.filter { it in kept }, dropped)
    }

    /**
     * 计算单个动作的肌群权重。
     *
     * `target` 是主动肌，`secondary` 是辅助肌。上游数据里 target 与 secondary 可能重复，
     * 重复时按主动肌计（取最大值，天然处理了这种重复）。
     *
     * @param bodyPart 可选的上游身体部位，仅在所有肌群名都无法识别时作为退路。
     * @return 肌群 → 权重（0 到 1），按 [DRAWABLE] 顺序；无法判定时为空表，不猜测。
     */
    fun weightsOf(target: String?, secondary: Iterable<String?>, bodyPart: String? = null): Map<MuscleGroup, Double> {
        val out = LinkedHashMap<MuscleGroup, Double>()
        fun addAll(names: Iterable<String?>, weight: Double) {
            for (name in names) {
                val slug = canonical(name) ?: continue
                out[slug] = maxOf(out[slug] ?: 0.0, weight)
            }
        }
        addAll(listOf(target), 1.0)
        addAll(secondary, SECONDARY_WEIGHT)
        if (out.isEmpty()) {
            BY_BODY_PART[bodyPart?.trim()?.lowercase()]?.forEach { (slug, weight) -> out[slug] = weight }
        }
        return DRAWABLE.filter { it in out }.associateWithTo(LinkedHashMap()) { out.getValue(it) }
    }

    /** 只保留正向刺激的肌群（权重 > 0），用于「这个动作练到了什么」。 */
    fun musclesOf(target: String?, secondary: Iterable<String?>, bodyPart: String? = null): Map<MuscleGroup, Double> =
        weightsOf(target, secondary, bodyPart).filterValues { it > 0.0 }

    /**
     * 训练负荷：给定若干 `(动作, 组数)`，算出每块肌肉的「有效组数」。
     *
     * 刻意不用公斤数做单位：100 kg 腿举和 12 kg 侧平举的重量差说明不了哪块肌肉更累。
     * 有效组数把辅助肌按 [SECONDARY_WEIGHT] 折算后累加，是能横向比较的同一量纲。
     *
     * @return 肌群 → 有效组数，按 [DRAWABLE] 顺序；组数 <= 0 的动作被忽略。
     */
    fun loadOf(items: Iterable<Pair<Map<MuscleGroup, Double>, Int>>): Map<MuscleGroup, Double> {
        val load = LinkedHashMap<MuscleGroup, Double>()
        for ((weights, sets) in items) {
            if (sets <= 0) continue
            for ((slug, weight) in weights) {
                if (weight <= 0.0) continue
                load[slug] = (load[slug] ?: 0.0) + weight * sets
            }
        }
        return DRAWABLE.filter { it in load }.associateWithTo(LinkedHashMap()) { load.getValue(it) }
    }

    /**
     * 着色档位 0–4。默认按「本次负荷最重的那块肌肉」相对归一，这样图永远读起来像一张平衡图。
     *
     * @param thresholds 可选绝对分档，元素为 `(下界, 档位, 是否严格大于)`，取最后一条命中规则。
     *                   传入时不再相对归一，用于恢复度、未训练等有固定语义的场景。
     */
    fun levelsOf(load: Map<MuscleGroup, Double>, thresholds: List<LevelRule>? = null): Map<MuscleGroup, Int> {
        if (thresholds != null) {
            return DRAWABLE.associateWith { slug ->
                val value = load[slug] ?: 0.0
                var level = 0
                for (rule in thresholds) {
                    val hit = if (rule.exclusive) value > rule.at else value >= rule.at
                    if (hit) level = rule.level.coerceIn(0, 4)
                }
                level
            }
        }
        val max = DRAWABLE.maxOfOrNull { load[it] ?: 0.0 } ?: 0.0
        return DRAWABLE.associateWith { slug ->
            val value = load[slug] ?: 0.0
            when {
                value <= 0.0 -> 0
                max <= 0.0 -> 0
                else -> ((value / max) * 4).let { Math.ceil(it).toInt() }.coerceIn(1, 4)
            }
        }
    }

    /** 练得最多的排在前面；没练到的按人体顺序排在后面。 */
    fun rankOf(load: Map<MuscleGroup, Double>): Ranked {
        val worked = DRAWABLE.filter { (load[it] ?: 0.0) > 0.0 }
            .sortedWith(compareByDescending<MuscleGroup> { load[it] ?: 0.0 }.thenBy { DRAWABLE.indexOf(it) })
        val missed = DRAWABLE.filter { (load[it] ?: 0.0) <= 0.0 }
        return Ranked(worked, missed)
    }

    data class Canonicalization(val muscles: List<MuscleGroup>, val dropped: List<String>)

    data class Ranked(val worked: List<MuscleGroup>, val missed: List<MuscleGroup>)

    /** 绝对分档规则：`value >= at`（或 `exclusive` 时为 `>`）时取 `level`，最后命中者胜。 */
    data class LevelRule(val at: Double, val level: Int, val exclusive: Boolean = false)
}
