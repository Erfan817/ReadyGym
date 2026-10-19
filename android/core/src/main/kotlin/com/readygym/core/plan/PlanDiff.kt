package com.readygym.core.plan

enum class ChangeKind(val label: String) {
    KEPT("保持"), LOAD_UP("加重"), LOAD_DOWN("减重"), VOLUME_UP("加量"), VOLUME_DOWN("减量"),
    ADJUSTED("微调"), REPLACED("替换"), ADDED("新增"), REMOVED("移除"),
}

data class PlanChange(val dayIndex: Int, val kind: ChangeKind, val before: PlanItem?, val after: PlanItem?) {
    val exerciseId: String get() = (after ?: before)!!.exerciseId
}

/**
 * Item-level comparison of two plan versions, day by day. Items are matched by exercise id;
 * unmatched removals and additions at the same day are paired up in order as replacements.
 */
object PlanDiff {
    fun between(old: WorkoutPlan, new: WorkoutPlan): List<PlanChange> = buildList {
        for (d in 0 until maxOf(old.days.size, new.days.size)) {
            val before = old.days.getOrNull(d)?.items.orEmpty()
            val after = new.days.getOrNull(d)?.items.orEmpty()
            val afterById = after.associateBy { it.exerciseId }
            val beforeIds = before.map { it.exerciseId }.toSet()
            val removed = before.filter { it.exerciseId !in afterById }
            val added = after.filter { it.exerciseId !in beforeIds }
            // Walk the new day in order so the diff reads like the plan the user will follow.
            val replacedBy = added.take(removed.size).zip(removed).toMap()
            after.forEach { item ->
                val prev = before.firstOrNull { it.exerciseId == item.exerciseId }
                when {
                    prev != null -> add(PlanChange(d, compare(prev, item), prev, item))
                    item in replacedBy -> add(PlanChange(d, ChangeKind.REPLACED, replacedBy[item], item))
                    else -> add(PlanChange(d, ChangeKind.ADDED, null, item))
                }
            }
            removed.drop(added.size).forEach { add(PlanChange(d, ChangeKind.REMOVED, it, null)) }
        }
    }

    fun hasChanges(old: WorkoutPlan, new: WorkoutPlan): Boolean = between(old, new).any { it.kind != ChangeKind.KEPT }

    private fun compare(a: PlanItem, b: PlanItem): ChangeKind {
        val la = a.loadKg ?: 0.0; val lb = b.loadKg ?: 0.0
        if (lb > la) return ChangeKind.LOAD_UP
        if (lb < la) return ChangeKind.LOAD_DOWN
        val va = volume(a); val vb = volume(b)
        if (vb > va) return ChangeKind.VOLUME_UP
        if (vb < va) return ChangeKind.VOLUME_DOWN
        return if (a == b) ChangeKind.KEPT else ChangeKind.ADJUSTED
    }

    private fun volume(i: PlanItem): Double =
        i.sets * (i.seconds?.toDouble() ?: (((i.repsMin ?: 0) + (i.repsMax ?: i.repsMin ?: 0)) / 2.0))
}
