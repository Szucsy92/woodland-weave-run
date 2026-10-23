package com.woodlandweave.run;

/** Pure policy shared by the Android bridge and host-side rule checks. */
public final class AudioOutputPolicy {
    private AudioOutputPolicy() { }

    public static boolean shouldMute(int mediaVolume, boolean streamMuted, int ringerMode) {
        // AudioManager: SILENT = 0, VIBRATE = 1, NORMAL = 2.
        return mediaVolume <= 0 || streamMuted || ringerMode != 2;
    }
}