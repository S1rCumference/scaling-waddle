package com.recorder.app.ui

import android.app.Activity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class Posture {
    /** Unfolded — the inner screen is in use. */
    OPEN,

    /** Half-open, hinge somewhere in between. */
    HALF_OPEN,

    /**
     * No folding feature is reported for this window. On a flip phone that is what the
     * cover screen looks like; on a normal phone it is simply always the case.
     */
    CLOSED_OR_FLAT,
}

/**
 * Fold state, for UI decisions only.
 *
 * Nothing here is wired to [com.recorder.app.service.RecordingService]: the recorder must
 * not know or care which way the hinge is pointing.
 */
object DevicePosture {

    fun flow(activity: Activity): Flow<Posture> =
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .map { info ->
                val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                when {
                    fold == null -> Posture.CLOSED_OR_FLAT
                    fold.state == FoldingFeature.State.HALF_OPENED -> Posture.HALF_OPEN
                    else -> Posture.OPEN
                }
            }
            .distinctUntilChanged()
}
