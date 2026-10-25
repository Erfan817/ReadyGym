package com.readygym.app

import com.readygym.core.Gender
import org.junit.Assert.assertEquals
import org.junit.Test

class CoachAvatarTest {
    @Test fun coachAvatarMatchesTheGenderTheUserGave() {
        assertEquals(R.drawable.coach_avatar_female, coachAvatarFor(Gender.FEMALE))
        assertEquals(R.drawable.coach_avatar_male, coachAvatarFor(Gender.MALE))
    }

    /** Male is the default portrait: not-yet-stated and other never fall back to a placeholder. */
    @Test fun maleIsTheDefaultBeforeTheUserSaysAnything() {
        assertEquals(R.drawable.coach_avatar_male, coachAvatarFor(Gender.OTHER))
        assertEquals(R.drawable.coach_avatar_male, coachAvatarFor(null))
    }
}
