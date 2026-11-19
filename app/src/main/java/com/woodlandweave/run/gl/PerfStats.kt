package com.woodlandweave.run.gl

/** Frame statistics written by the render thread and read by the on-screen overlay. */
class PerfStats {
    @Volatile var fps = 0f
    @Volatile var avgMs = 0f
    /** The slowest frame of the last half second: a stutter shows up here long before it shows in the average. */
    @Volatile var worstMs = 0f
    @Volatile var density = 0f
    @Volatile var tier = ""
}
