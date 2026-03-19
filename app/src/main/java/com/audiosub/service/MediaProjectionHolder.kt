package com.audiosub.service

import android.media.projection.MediaProjection
import android.util.Log

private const val TAG = "MediaProjectionHolder"

/**
 * Singleton holder for the [MediaProjection] instance.
 *
 * The [MediaProjection] is obtained in [MainActivity] and must survive
 * the transition to [AudioCaptureService]. Storing it here avoids Intent
 * parcelization issues on Android 13+ (MediaProjection is not Parcelable).
 */
object MediaProjectionHolder {
    private var projection: MediaProjection? = null

    fun set(mp: MediaProjection) {
        projection?.stop()
        projection = mp
        Log.i(TAG, "MediaProjection set")
    }

    fun get(): MediaProjection? = projection

    fun release() {
        projection?.stop()
        projection = null
        Log.i(TAG, "MediaProjection released")
    }
}
