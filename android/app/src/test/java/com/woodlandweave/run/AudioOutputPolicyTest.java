package com.woodlandweave.run;

/** Runs on a plain JDK, without an Android device or SDK. */
public final class AudioOutputPolicyTest {
    private static void check(boolean condition, String rule) {
        if (!condition) throw new AssertionError(rule);
    }

    public static void main(String[] args) {
        check(AudioOutputPolicy.shouldMute(5, false, 0), "Silent mode must mute even with media volume above zero");
        check(AudioOutputPolicy.shouldMute(5, false, 1), "Vibrate mode must mute even with media volume above zero");
        check(AudioOutputPolicy.shouldMute(0, false, 2), "Zero media volume must mute in normal mode");
        check(AudioOutputPolicy.shouldMute(5, true, 2), "Explicit media stream mute must stop output");
        check(!AudioOutputPolicy.shouldMute(5, false, 2), "Normal mode with audible media volume must allow sound");
        System.out.println("5 Android audio policy checks passed");
    }
}