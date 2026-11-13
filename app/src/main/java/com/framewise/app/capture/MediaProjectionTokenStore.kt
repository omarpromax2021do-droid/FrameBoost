package com.framewise.app.capture

import android.content.Intent

/**
 * Stores a single-use MediaProjection consent token in memory and clears it on
 * consumption so Android 14-17+ never sees a reused MediaProjection Intent.
 */
object MediaProjectionTokenStore {
    data class ProjectionToken(
        val resultCode: Int,
        val data: Intent
    )

    private var pendingToken: ProjectionToken? = null

    @Synchronized
    fun store(resultCode: Int, data: Intent) {
        pendingToken = ProjectionToken(resultCode, data)
    }

    @Synchronized
    fun consume(): ProjectionToken? {
        val token = pendingToken
        pendingToken = null
        return token
    }

    @Synchronized
    fun clear() {
        pendingToken = null
    }
}
