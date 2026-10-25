package com.readygym.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review gate's acceptance criteria are partly negative: "no scattered `tween(` / `spring(`
 * literals outside the token file", and "no second motion state source". Those are checked here,
 * against the real source tree rather than by trusting the diff.
 *
 * This is a plain JUnit test (no Robolectric, no Compose) so it costs nothing and can never be
 * skipped for environment reasons.
 */
class MotionTokenTest {

    private val appSources: File = listOf(
        File("src/main/kotlin/com/readygym/app"),
        File("app/src/main/kotlin/com/readygym/app"),
        File("../app/src/main/kotlin/com/readygym/app"),
    ).firstOrNull { it.isDirectory } ?: error("Cannot locate the app sources from ${File(".").absolutePath}")

    private val tokenFile = "Motion.kt"

    private fun sources(): List<File> = appSources.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.name != tokenFile }
        .toList()

    @Test fun sourcesWereFound() {
        assertTrue("Expected .kt files under $appSources", sources().size > 10)
    }

    @Test fun noAnimationLiteralsOutsideTheTokenFile() {
        val banned = listOf(Regex("""\btween\s*\("""), Regex("""\bspring\s*\("""))
        val offenders = buildList {
            for (file in sources()) {
                file.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    if (banned.any { it.containsMatchIn(code) }) {
                        add("${file.name}:${index + 1}: ${line.trim()}")
                    }
                }
            }
        }
        assertTrue(
            "Animation specs must come from SunnyMotion (Motion.kt); found:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** The only class allowed to hold a motion state; everything else reads it. */
    @Test fun thereIsExactlyOneMotionStateSource() {
        val declaring = sources().filter { file ->
            file.readLines().any { it.contains("enum class CoachUiState") }
        }
        assertTrue("CoachUiState must be declared exactly once, was ${declaring.map { it.name }}",
            declaring.size == 1)

        // No screen may keep its own "what should be playing" flag alongside the reducer. The
        // token plumbing (Motion.kt, SunnyTheme.kt) and the animation components may mention
        // motion; a *state holder* may not.
        val duplicateFlags = sources().filter { file ->
            file.name !in setOf("SunnyTheme.kt", "CoachMotion.kt", "CoachChatScreen.kt") &&
                file.readLines().any { line ->
                    val code = line.substringBefore("//").trim()
                    (code.startsWith("var ") || code.startsWith("val ")) &&
                        Regex("""\b(uiMotion|motionState|animState|animationState|playingAnim\w*)\b""")
                            .containsMatchIn(code)
                }
        }
        assertTrue("A second motion state was introduced in: ${duplicateFlags.map { it.name }}",
            duplicateFlags.isEmpty())
    }

    /** Duration literals must be the token's two steps, not new numbers per screen. */
    @Test fun noAdHocAnimationDurations() {
        val offenders = buildList {
            for (file in sources()) {
                file.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    val duration = Regex("""durationMillis\s*=\s*(\d+)""").find(code)
                    if (duration != null && duration.groupValues[1].toInt() !in setOf(
                            SunnyMotion.DurationShort, SunnyMotion.DurationMedium,
                        )
                    ) {
                        add("${file.name}:${index + 1}: ${line.trim()}")
                    }
                }
            }
        }
        assertTrue("Durations must be DurationShort/DurationMedium; found:\n" + offenders.joinToString("\n"),
            offenders.isEmpty())
    }
}
