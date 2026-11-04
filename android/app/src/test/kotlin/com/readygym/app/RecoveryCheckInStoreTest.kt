package com.readygym.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryCheckInStoreTest {
    @Test fun checkpointSurvivesRecreationAndCannotMoveBackwards() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("recovery_check_in", Context.MODE_PRIVATE).edit().clear().commit()
        val store = RecoveryCheckInStore(context)
        assertEquals(0L, store.handledThroughId())
        store.markHandled(36)
        val restored = RecoveryCheckInStore(context)
        assertEquals(36L, restored.handledThroughId())
        restored.markHandled(35)
        assertEquals(36L, RecoveryCheckInStore(context).handledThroughId())
    }
}
