package com.woodlandweave.run.audio

object AudioOutputPolicy {
    /** AudioManager ringer modes: SILENT = 0, VIBRATE = 1, NORMAL = 2. */
    fun shouldMute(mediaVolume: Int, streamMuted: Boolean, ringerMode: Int) =
        mediaVolume <= 0 || streamMuted || ringerMode != 2
}
