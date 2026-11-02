package com.readygym.app

import com.readygym.core.exercise.ExerciseLibrary

/** Where in the gym an item lives; drives the filter chips. */
enum class EquipmentCategory(val label: String) {
    MACHINE("固定器械"), CABLE("绳索器械"), BARBELL("杠铃区"), DUMBBELL("哑铃区"), CARDIO("有氧区"),
}

/**
 * One piece of gym equipment and how to use it. [setupSteps] are the adjustments made before the
 * first rep (seat height, back pad, handles); [usageSteps] is the movement itself.
 *
 * [demoGif] names a demonstration GIF in the ExerciseGymGifsDB dataset (relative path such as
 * `lats/cable-lat-pulldown-full-range-of-motion.gif`); the ReadyGym server serves it at
 * `/gifs/<path>` and the guide plays it above the text steps. Null means the dataset has no
 * honest match for this station and the text stands alone — no substitution by a lookalike.
 *
 * [beginnerNote] is set for the barbell stations whose main lifts are marked HIGH_RISK in the
 * exercise library: the guide still explains how the station works, but says out loud why a novice
 * plan will not schedule it and what to do instead. Nothing here is a medical claim.
 */
data class EquipmentGuideItem(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val category: EquipmentCategory,
    val beginnerFriendly: Boolean = true,
    val setupSteps: List<String>,
    val usageSteps: List<String>,
    val safety: List<String>,
    val mistakes: List<String>,
    val relatedExerciseIds: List<String> = emptyList(),
    val beginnerNote: String? = null,
    val demoGif: String? = null,
)

object EquipmentGuideCatalog {

    val all: List<EquipmentGuideItem> = listOf(
        item("lat-pulldown", "高位下拉机", listOf("下拉", "背阔肌", "lat pulldown"), EquipmentCategory.MACHINE,
            setup = listOf("调座高：大腿被海绵压辊压住时，双脚能平放地面", "调坐垫前后：坐直时握把在头顶正上方，不用够",
                "选择配重：插销插到底，第一组先用能轻松做 12 次的重量"),
            usage = listOf("坐好、压辊压腿，双手比肩略宽握横杆", "挺胸、肩胛先下沉，把杆拉到锁骨附近", "肘部垂直向下发力，不用力时控制杆慢慢回放", "回放到手臂伸直但肩膀不耸起，再做下一次"),
            safety = listOf("下拉时身体不要大幅后仰借力", "杆回落时不要让重量把肩膀硬拉向上", "手腕保持中立，不要勾腕"),
            mistakes = listOf("拉到脑后（容易伤肩和颈椎），应拉到胸前", "重量太大靠晃动身体完成", "半程动作：没拉到位也没放到底"),
            related = listOf("lat-pulldown"),
            demo = "lats/cable-lat-pulldown-full-range-of-motion.gif",),
        item("seated-row", "坐姿划船机", listOf("划船", "绳索划船", "seated row"), EquipmentCategory.CABLE,
            setup = listOf("脚踩踏板，膝盖微弯但不是锁死", "调坐垫：握手柄时背部能挺直、手臂伸直", "配重从轻开始：能标准完成 12 次再加"),
            usage = listOf("挺胸坐直，腹部收紧稳定躯干", "肘部贴身向后拉，把手拉到腹部", "肩胛向后夹紧停半秒", "慢慢放回，让肩胛自然前伸"),
            safety = listOf("不要弓背，全程背部保持平直", "回放时控制速度，不要让配重牵着你冲", "出现腰部不适就停下"),
            mistakes = listOf("身体大幅前后摇摆借力", "耸肩发力，应该用背部", "拉得太高变成练肩"),
            related = listOf("seated-cable-row"),
            demo = "upper-back/cable-low-seated-row.gif",),
        item("leg-press", "腿举机（倒蹬机）", listOf("倒蹬", "腿举", "leg press"), EquipmentCategory.MACHINE,
            setup = listOf("调座椅/背垫：躺好后臀部、腰部贴紧垫子", "双脚与肩同宽踩踏板，脚掌中前部着板", "检查安全挡位，重量从能做 12 次的开始"),
            usage = listOf("解开安全锁，屈膝让踏板缓慢下降", "降到大腿和小腿约 90 度（再深需髋部不离垫）", "脚掌发力蹬回，膝盖伸直但不锁死", "动作全程腰臀不离背垫"),
            safety = listOf("膝盖绝不向内扣", "下降不要过深，臀部离开垫子会压迫腰椎", "蹬起最后不要猛然锁死膝盖", "手不要扶在膝盖运动轨迹上"),
            mistakes = listOf("重量过大导致臀部离垫、腰部代偿", "幅度过小自欺", "双脚放太高只练臀、放太低压膝盖——按目标微调即可"),
            related = listOf("leg-press"),
            demo = "glutes/sled-45-leg-press.gif",),
        item("chest-press", "坐姿推胸机", listOf("推胸", "胸推", "chest press"), EquipmentCategory.MACHINE,
            setup = listOf("调座高：把手与乳头线（胸的中部）齐平", "背和头贴紧靠垫，双脚踩实", "两侧手臂活动范围先调一致"),
            usage = listOf("握把向前推，肘部不完全锁死", "推到胸前自然伸直，停半秒", "缓慢回到手在胸前 1–2 拳的位置", "重复，呼吸：推出呼气、回收吸气"),
            safety = listOf("肩部疼痛（不是酸）时立刻停止", "不要耸肩，肩胒贴住靠垫", "重量以能控制为准，不与人攀比"),
            mistakes = listOf("离背垫太远、身体扭动借力", "推起速度过快", "手柄位置过高或过低练偏"),
            related = listOf("machine-chest-press"),
            demo = "pectorals/lever-chest-press.gif",),
        item("shoulder-press-machine", "坐姿推肩机", listOf("推肩", "肩推", "shoulder press"), EquipmentCategory.MACHINE,
            setup = listOf("调座高：把手与耳朵/肩线齐平", "背贴靠垫，腹部收紧", "轻配重起步"),
            usage = listOf("向上推起把手，肘部接近伸直不锁死", "头部保持中立，不顶靠垫抬脖子", "缓慢下放到把手接近耳朵", "重复完成次数"),
            safety = listOf("肩关节弹响伴疼痛时停止", "下放不要过深", "手腕保持中立"),
            mistakes = listOf("靠腰往前顶出 arch 借力", "半程推、半程放", "脖子前伸"),
            related = listOf("machine-shoulder-press"),
            demo = "delts/cable-shoulder-press.gif",),
        item("leg-extension", "腿屈伸机", listOf("练大腿前侧", "腿屈伸", "leg extension"), EquipmentCategory.MACHINE,
            setup = listOf("调靠背：膝盖窝贴住坐垫前端", "调挡辊位置：正好卡在脚踝上方、小腿下端", "轻重量起步"),
            usage = listOf("小腿向上抬起直到膝盖伸直", "顶端停半秒，感受大腿前侧收紧", "缓慢放回但不让配重砸下", "完成次数内保持节奏一致"),
            safety = listOf("膝盖有伤病史（尤其髌腱）先问专业人士", "不要用甩腿借力", "伸直即可，不要过度反弓"),
            mistakes = listOf("重量过大用惯性甩起", "臀部离开坐垫", "下放太快失去张力"),
            related = listOf("leg-extension"),
            demo = "quads/lever-leg-extension.gif",),
        item("leg-curl", "腿弯举机", listOf("大腿后侧", "腘绳肌", "leg curl"), EquipmentCategory.MACHINE,
            setup = listOf("俯卧（或坐姿机型按图示）：挡辊卡在脚踝上方", "调档位：膝盖关节线对准转轴", "轻重量起步"),
            usage = listOf("小腿向臀部方向弯起", "顶端停半秒", "缓慢放回伸直", "髋部保持贴垫不抬"),
            safety = listOf("腰不要反弓代偿", "放下阶段更要慢（离心最容易拉伤）", "腘绳肌拉伤恢复期不要做"),
            mistakes = listOf("抬头弓背借力", "幅度不足", "速度过快"),
            related = listOf("lying-leg-curl"),
            demo = "hamstrings/lever-lying-leg-curl.gif",),
        item("cable-crossover", "龙门架（绳索训练器）", listOf("绳索", "钢索", "夹胸", "cable"), EquipmentCategory.CABLE,
            setup = listOf("两侧滑轮调到需要的高度：下压在高位、夹胸在中高或低位", "装上需要的把手（直杆/绳索/单D把）", "轻配重起步，双手各握一侧"),
            usage = listOf("面向或背向立柱站稳，膝盖微弯", "肩胛稳定，沿设计轨迹匀速拉动把手", "顶峰收紧目标肌肉半秒", "缓慢回放到起始张力位置"),
            safety = listOf("换把手/调滑轮前确认配重插销插稳", "不要站在配重片中间区域", "双手同时控制，避免单侧突然脱力"),
            mistakes = listOf("重量太大导致身体大幅摇摆", "站姿不稳", "动作轨迹乱甩"),
            related = listOf("triceps-pushdown"),
            demo = "triceps/cable-pushdown.gif",),
        item("calf-raise-machine", "站姿提踵机", listOf("提踵", "小腿", "calf"), EquipmentCategory.MACHINE,
            setup = listOf("肩垫压住斜方肌（不压脖子），前脚掌站在踏板上", "脚跟下沉到小腿有拉伸感为起始位", "轻重量起步"),
            usage = listOf("前脚掌发力把脚跟抬到最高", "顶端停半秒", "缓慢下放到底，感受小腿拉伸", "重复，节奏稳定"),
            safety = listOf("膝盖保持自然伸直但不锁死", "踩踏板前脚掌要踩实，防打滑", "跟腱有伤病史先咨询专业人士"),
            mistakes = listOf("用弹跳借力", "幅度过小", "脚跟从不下降到底"),
            related = listOf("machine-calf-raise"),
            demo = "calves/lever-standing-calf-raise.gif",),
        item("pec-deck", "器械夹胸机", listOf("夹胸", "飞鸟机", "pec deck"), EquipmentCategory.MACHINE,
            setup = listOf("调座高：把手与胸中部齐平", "背贴靠垫，决定用把手还是护垫版本", "轻重量起步"),
            usage = listOf("手臂微弯固定肘角，向胸前合拢", "合拢停半秒挤压胸部", "缓慢打开到胸有拉伸感", "肘角全程不变"),
            safety = listOf("肘部不要过度后展", "肩膀不适时减小幅度", "不憋气"),
            mistakes = listOf("肘角变化变成推", "重量过大开合幅度失控", "弓背"),
            related = listOf("pec-deck"),
            demo = "pectorals/lever-seated-fly.gif",),
        item("barbell-rack", "杠铃与深蹲架", listOf("杠铃", "深蹲架", "奥杆", "barbell"), EquipmentCategory.BARBELL,
            beginnerFriendly = false,
            setup = listOf("调保护销：放在你最低点略下的位置", "杆放在斜方肌上（高杠）或按教练指导定位", "片重对称上杠并用卡箍锁死"),
            usage = listOf("起杆后退两步站定，脚与肩同宽或略宽", "屈髋屈膝下蹲，膝盖与脚尖方向一致", "蹲到能保持背部平直的深度", "脚掌发力站起，肘部不要放松"),
            safety = listOf("一定要调好保护销再练，失败时让杆落在销上", "没有保护架时不要独自上大重量", "腰背任何疼痛立即结束动作"),
            mistakes = listOf("膝盖内扣", "脚跟离地", "贪重量牺牲深度和背角"),
            related = listOf("barbell-squat", "barbell-deadlift", "barbell-rdl"),
            demo = "glutes/barbell-high-bar-squat.gif",
            beginnerNote = "杠铃深蹲、硬拉这类大重量复合动作已从新手和恢复训练者的计划里排除：它们需要现场教练教学和保护架。想练腿可以用倒蹬机、哑铃高脚杯深蹲替代。"),
        item("bench-press", "卧推凳与杠铃架", listOf("卧推", "平凳", "bench press"), EquipmentCategory.BARBELL,
            beginnerFriendly = false,
            setup = listOf("调杠铃架挂钩高度：躺下时伸手能轻松出杆", "躺好：眼睛在杆正下方，双脚踩实地面", "若有保护销，调到贴近胸口上沿的位置"),
            usage = listOf("握距比肩略宽，手腕中立", "出杆缓慢下放到胸口中部", "肘部约 45–70 度，不要完全打开成直线", "推起时背部自然收紧，臀部不离凳"),
            safety = listOf("没有保护时不要把杠铃杆夹到脖子轨迹上方", "大重量务必有人保护", "肩部疼痛立即停止"),
            mistakes = listOf("屁股抬离凳面", "杆弹胸借力", "手腕后折"),
            related = listOf("barbell-bench-press", "dumbbell-bench-press"),
            demo = "pectorals/barbell-bench-press.gif",
            beginnerNote = "杠铃卧推对新手属于高风险动作，已从新手计划里排除；可先用坐姿推胸机或哑铃卧推（有人保护时）建立力量。"),
        item("dumbbell-area", "哑铃区与可调节凳", listOf("哑铃", "哑铃凳", "dumbbell"), EquipmentCategory.DUMBBELL,
            setup = listOf("按顺序取放哑铃，不挡通道", "调节凳：平凳/上斜（约 30–45 度）按动作需要", "重量从能标准完成 10–12 次的开始"),
            usage = listOf("先学会动作再加重：凳上动作先躺稳、脚踩实", "双侧同时起落，控制速度", "组间把哑铃放回架位，不扔掷", "练完归位，方便下一个人"),
            safety = listOf("拿放哑铃时用腿发力，弯腰提重物注意腰", "哑铃卧推等头顶动作确保周围无人", "力竭前留 1–2 次余量，先学动作"),
            mistakes = listOf("甩动借力", "选重量只看别人", "用完不归位"),
            related = listOf("dumbbell-bench-press", "goblet-squat", "dumbbell-row", "dumbbell-shoulder-press"),
            demo = "pectorals/dumbbell-bench-press.gif",),
        item("treadmill", "跑步机", listOf("跑台", "treadmill"), EquipmentCategory.CARDIO,
            setup = listOf("上机前站在侧踏板，夹好安全磁扣（绳夹在衣物上）", "从低速（步行）开始再逐步提速", "需要时调坡度"),
            usage = listOf("提速前先适应步速，眼睛看前方", "跑步中不要低头看脚", "结束前逐步降速到步行，再停", "按停止键完全停稳后，从侧踏板下机"),
            safety = listOf("安全磁扣是急停的最后保障，必须夹好", "不要在履带上跨上跨下", "感到头晕立即按急停扶稳"),
            mistakes = listOf("上来就跑很快", "抓着扶手弓背走", "直接跳下运动中的跑台"),
            related = listOf(),
            demo = "cardio/walking-on-incline-treadmill.gif",),
        item("elliptical", "椭圆机", listOf("椭圆", "elliptical"), EquipmentCategory.CARDIO,
            setup = listOf("踩上踏板，双手轻握把手", "从最低阻力起步"),
            usage = listOf("整脚踩实踏板，脚跟不刻意抬起", "推拉把手与下肢协调", "身体直立、核心收紧", "结束前逐步降阻力降速度"),
            safety = listOf("不反向猛蹬", "踏板湿滑时先擦干", "膝盖不适先降阻力"),
            mistakes = listOf("只用脚不用手（或反之）", "脚尖发力踮脚", "阻力为零空转"),
            related = listOf(),
            demo = "cardio/walk-elliptical-cross-trainer.gif",),
        item("rowing-machine", "划船机", listOf("赛艇", "划船器", "rowing"), EquipmentCategory.CARDIO,
            setup = listOf("调阻力于低档，脚带绑紧前脚掌", "坐好，膝盖微弯在滑轨内"),
            usage = listOf("顺序：蹬腿 → 身体后倾 → 手拉到肋骨", "回程反序：伸手 → 身体前倾 → 屈膝", "全程背部平直、肩胛不耸", "按节奏呼吸：拉时呼气"),
            safety = listOf("腰背不要弓起", "不要用手腕猛钩", "阻力循序渐进"),
            mistakes = listOf("先用手拉再蹬腿", "回程时膝盖先弯被手挡住", "拉到胸口过高"),
            related = listOf()),
    )

    /** Case-insensitive match on name, aliases and category label; a blank query returns everything. */
    fun search(query: String, category: EquipmentCategory? = null): List<EquipmentGuideItem> {
        val q = query.trim().lowercase()
        return all.filter { item ->
            (category == null || item.category == category) &&
                (q.isBlank() || q in item.name.lowercase() || item.aliases.any { q in it.lowercase() } || q in item.category.label)
        }
    }

    fun find(id: String): EquipmentGuideItem? = all.firstOrNull { it.id == id }

    /** The photo-recognition prompt: catalog machines by name, other stations as a short free answer. */
    fun recognitionPrompt(): String =
        "这是用户在健身房拍的器械照片。\n" +
            "如果它就是下面清单中的一台，只回答清单里的名字：\n" +
            all.joinToString("、") { it.name } + "\n" +
            "如果不是清单里的，用不超过8个字回答它是什么器械（例如：蝴蝶机、史密斯机、龙门架、" +
            "哈克深蹲机、坐姿腿弯举机、髋外展训练机、站姿提踵机、山羊挺身凳、辅助引体机）。\n" +
            "如果看不清或不是健身器械，只回答：不确定"

    /**
     * Match a vision reply back to a catalog item. The model answers in prose (sometimes English),
     * so the match is containment over names, ids and aliases, preferring the longest key; an
     * ambiguous tie (e.g. 推举 could be chest or shoulder press) means no auto-selection — the
     * user picks by hand rather than the app guessing.
     */
    fun matchEquipment(reply: String): EquipmentGuideItem? {
        val text = reply.lowercase()
        if (text.isBlank() || text.contains("不确定")) return null
        val hits = all.mapNotNull { item ->
            val key = (listOf(item.name, item.id) + item.aliases)
                .map { it.lowercase() }
                .filter { text.contains(it) }
                .maxByOrNull { it.length } ?: return@mapNotNull null
            item to key.length
        }
        val top = hits.maxOfOrNull { it.second } ?: return null
        return hits.filter { it.second == top }.map { it.first }.singleOrNull()
    }

    /**
     * One gym machine beyond the 16 curated guides: how the vision model may name it, and which
     * bundled-library exercises run on it (each ships a demo GIF). The guide has no setup/usage
     * steps for these — instead of pretending, it offers the exercises.
     */
    data class ExtraMachine(val keywords: List<String>, val label: String, val exerciseIds: List<String>)

    val extraMachines: List<ExtraMachine> = listOf(
        ExtraMachine(listOf("蝴蝶", "pec deck", "夹胸机"), "蝴蝶机（夹胸器）",
            listOf("lever-seated-fly", "pec-deck", "cable-standing-fly", "lever-seated-reverse-fly")),
        ExtraMachine(listOf("史密斯", "smith"), "史密斯机",
            listOf("smith-squat", "smith-bench-press", "smith-shoulder-press", "smith-sumo-squat")),
        ExtraMachine(listOf("龙门", "crossover", "大飞鸟"), "龙门架（绳索交叉）",
            listOf("cable-standing-fly", "cable-incline-fly", "cable-low-fly", "triceps-pushdown")),
        ExtraMachine(listOf("哈克", "hack"), "哈克深蹲机",
            listOf("sled-hack-squat", "barbell-hack-squat", "hack-calf-raise")),
        ExtraMachine(listOf("腿弯举", "leg curl"), "腿弯举机",
            listOf("lever-seated-leg-curl", "lying-leg-curl")),
        ExtraMachine(listOf("外展", "内收", "abduct", "adduct"), "髋外展/内收机",
            listOf("lever-seated-hip-abduction", "lever-seated-hip-adduction", "cable-hip-adduction")),
        ExtraMachine(listOf("提踵", "calf raise"), "提踵机",
            listOf("machine-calf-raise", "calf-raise", "donkey-calf-raise", "hack-calf-raise")),
        ExtraMachine(listOf("山羊", "背伸", "hyperextension"), "山羊挺身凳",
            listOf("hyperextension")),
        ExtraMachine(listOf("辅助引体", "assisted"), "辅助引体机",
            listOf("lever-assisted-chin-up", "assisted-pull-up")),
    )

    /** A free-form machine name from the photo reply → the extra-machine suggestion, if known. */
    fun matchExtraMachine(reply: String): ExtraMachine? {
        val text = reply.lowercase()
        if (text.isBlank() || text.contains("不确定")) return null
        val hits = extraMachines.mapNotNull { m ->
            val key = m.keywords.map { it.lowercase() }.filter { text.contains(it) }.maxByOrNull { it.length }
                ?: return@mapNotNull null
            m to key.length
        }
        val top = hits.maxOfOrNull { it.second } ?: return null
        return hits.filter { it.second == top }.map { it.first }.singleOrNull()
    }

    /**
     * Catalog integrity: every related exercise must exist in the bundled library, so a guide page
     * can never promise a demo for an id the app cannot look up.
     */
    fun unknownExerciseIds(library: ExerciseLibrary): List<String> =
        all.flatMap { it.relatedExerciseIds }.filter { it !in library }.distinct()

    private fun item(id: String, name: String, aliases: List<String>, category: EquipmentCategory,
        beginnerFriendly: Boolean = true, setup: List<String>, usage: List<String>,
        safety: List<String>, mistakes: List<String>, related: List<String> = emptyList(),
        beginnerNote: String? = null, demo: String? = null) =
        EquipmentGuideItem(id, name, aliases, category, beginnerFriendly, setup, usage, safety, mistakes, related, beginnerNote, demo)
}
