package com.wusper.x86.audio

import android.content.Context
import com.winlator.alsaserver.ALSAClient
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.components.ALSAServerComponent
import java.io.File

/**
 * 進程內 ALSA server：客體 winealsa／android_aserver → 宿主 AAudio。
 */
class InProcessAudio(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val imageFsRoot = File(appCtx.filesDir, "engine/imagefs")
    private var component: ALSAServerComponent? = null

    val soundDir: File get() = File(imageFsRoot, "tmp/.sound")

    fun start() {
        if (component != null) return
        imageFsRoot.mkdirs()
        soundDir.mkdirs()
        val cfg = UnixSocketConfig.create(imageFsRoot.absolutePath, UnixSocketConfig.ALSA_SERVER_PATH)
        component = ALSAServerComponent(appCtx, cfg, ALSAClient.Options()).also { it.start() }
    }

    fun stop() {
        component?.stop()
        component = null
    }

    fun socketPath(): String = File(imageFsRoot, "tmp/.sound/AS0").absolutePath
}
