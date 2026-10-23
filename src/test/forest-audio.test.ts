import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ForestDrone } from "@/lib/forest/drone";

describe("Continuous forest soundtrack", () => {
  let audio: ForestDrone;
  let ctx: {
    state: string; currentTime: number; onstatechange: (() => void) | null;
    resume: ReturnType<typeof vi.fn>; suspend: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn>;
    createBufferSource: ReturnType<typeof vi.fn>;
  };
  let frequency: { value: number; setTargetAtTime: ReturnType<typeof vi.fn>; cancelScheduledValues: ReturnType<typeof vi.fn>; setValueAtTime: ReturnType<typeof vi.fn>; linearRampToValueAtTime: ReturnType<typeof vi.fn> };
  let gain: { value: number; setTargetAtTime: ReturnType<typeof vi.fn>; cancelScheduledValues: ReturnType<typeof vi.fn>; setValueAtTime: ReturnType<typeof vi.fn> };
  let start: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    frequency = { value: 0, setTargetAtTime: vi.fn(), cancelScheduledValues: vi.fn(), setValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn() };
    gain = { value: 0, setTargetAtTime: vi.fn(), cancelScheduledValues: vi.fn(), setValueAtTime: vi.fn() };
    start = vi.fn();
    ctx = {
      state: "suspended", currentTime: 0, onstatechange: null,
      resume: vi.fn(async () => { ctx.state = "running"; }),
      suspend: vi.fn(async () => { ctx.state = "suspended"; }), close: vi.fn(async () => {}),
      createBufferSource: vi.fn(() => ({ connect: vi.fn(), start, stop: vi.fn(), loop: false })),
    };
    vi.stubGlobal("AudioContext", class {
      constructor() { return Object.assign(ctx, {
        createBiquadFilter: () => ({ frequency, Q: { value: 0 }, connect: vi.fn() }),
        createGain: () => ({ gain, connect: vi.fn() }),
        decodeAudioData: async () => ({ sampleRate: 44100, length: 4410, numberOfChannels: 1, getChannelData: () => new Float32Array(4410) }),
        createBuffer: (channels: number, length: number, sampleRate: number) => {
          const data = Array.from({ length: channels }, () => new Float32Array(length));
          return { numberOfChannels: channels, length, sampleRate, getChannelData: (c: number) => data[c] };
        },
      }); }
    });
    vi.stubGlobal("fetch", vi.fn(async () => ({ ok: true, arrayBuffer: async () => new ArrayBuffer(4) })));
    audio = new ForestDrone();
  });
  afterEach(() => { audio.dispose(); vi.useRealTimers(); vi.unstubAllGlobals(); delete (window as unknown as Record<string, unknown>)["AndroidOrientation"]; delete (window as unknown as Record<string, unknown>)["AndroidAudio"]; });

  it("plays a 100 ms shorter, crossfaded loop so the seam has no silence gap", async () => {
    let made: { length: number; getChannelData: (c: number) => Float32Array } | null = null;
    audio.unlock();
    const live = ctx as unknown as { decodeAudioData: unknown; createBuffer: (...a: [number, number, number]) => never };
    live.decodeAudioData = async () => {
      const d = new Float32Array(44100).fill(0.5);
      return { sampleRate: 44100, length: 44100, numberOfChannels: 1, getChannelData: () => d };
    };
    const original = live.createBuffer;
    live.createBuffer = (...a: [number, number, number]) => (made = original(...a));
    await vi.waitFor(() => expect(start).toHaveBeenCalledOnce());
    if (!made) throw new Error("Loop buffer was not created");
    const loop = made as { length: number; getChannelData: (c: number) => Float32Array };
    expect(loop.length).toBe(44100 - 4410);
    expect(loop.getChannelData(0)[0]).toBeCloseTo(0.5);
  });

  it("starts the menu loop without a tap at 220 Hz", async () => {
    audio.unlock();
    await vi.waitFor(() => expect(start).toHaveBeenCalledOnce());
    expect(ctx.resume).toHaveBeenCalled();
    expect(frequency.value).toBe(220);
    expect(gain.setTargetAtTime).toHaveBeenLastCalledWith(0.3, 0, 0.8);
  });

  it("opens the same loop to 4000 Hz while running and muffles it after falling", async () => {
    audio.unlock();
    await vi.waitFor(() => expect(start).toHaveBeenCalledOnce());
    audio.setMode(true, true);
    expect(frequency.setTargetAtTime).toHaveBeenLastCalledWith(4000, 0, 0.65);
    audio.setMode(true, false);
    expect(frequency.setTargetAtTime).toHaveBeenLastCalledWith(220, 0, 0.65);
    expect(ctx.createBufferSource).toHaveBeenCalledOnce();
  });

  it("keeps the impact muffled for 500 ms", () => {
    audio.unlock(); audio.setMode(true, true); audio.impactDip();
    expect(frequency.linearRampToValueAtTime).toHaveBeenCalledWith(220, 0.03);
    expect(frequency.setTargetAtTime).toHaveBeenLastCalledWith(4000, 0.5, 0.18);
  });

  it("honours the saved sound-off setting", () => {
    audio.setMode(false, false); audio.unlock();
    expect(gain.setValueAtTime).toHaveBeenLastCalledWith(0, 0);
    expect(ctx.resume).not.toHaveBeenCalled();
  });

  it("suspends all audio at zero native media volume and restores it above zero", async () => {
    vi.useFakeTimers();
    let muted = true;
    vi.stubGlobal("AndroidAudio", { isMuted: () => muted });
    audio.unlock();
    expect(ctx.resume).not.toHaveBeenCalled();
    expect(gain.setValueAtTime).toHaveBeenLastCalledWith(0, 0);
    muted = false;
    await vi.advanceTimersByTimeAsync(200);
    expect(ctx.state).toBe("running");
    muted = true;
    await vi.advanceTimersByTimeAsync(200);
    expect(ctx.suspend).toHaveBeenCalledOnce();
    expect(ctx.state).toBe("suspended");
    muted = false;
    audio.setMode(false, false);
    await vi.advanceTimersByTimeAsync(400);
    expect(ctx.state).toBe("suspended");
    audio.setMode(true, false);
    expect(ctx.state).toBe("running");
    audio.suspend();
    await vi.advanceTimersByTimeAsync(400);
    expect(ctx.state).toBe("suspended");
    audio.dispose();
    const attempts = ctx.resume.mock.calls.length;
    await vi.advanceTimersByTimeAsync(400);
    expect(ctx.resume).toHaveBeenCalledTimes(attempts);
  });

  it("stops active output exactly when the sound switch is turned off", () => {
    audio.unlock();
    audio.setMode(false, false);
    expect(gain.cancelScheduledValues).toHaveBeenCalledWith(0);
    expect(gain.setValueAtTime).toHaveBeenLastCalledWith(0, 0);
    expect(ctx.state).toBe("suspended");
  });

  it("retries suspended native startup and stops retrying after disposal", async () => {
    vi.useFakeTimers();
    (window as unknown as Record<string, unknown>)["AndroidOrientation"] = { set: vi.fn() };
    ctx.resume.mockImplementation(async () => {});
    audio.unlock();
    await vi.advanceTimersByTimeAsync(750);
    expect(ctx.resume.mock.calls.length).toBeGreaterThan(1);
    audio.dispose();
    const attempts = ctx.resume.mock.calls.length;
    await vi.advanceTimersByTimeAsync(1000);
    expect(ctx.resume).toHaveBeenCalledTimes(attempts);
  });
});