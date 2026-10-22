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
  let gain: { value: number; setTargetAtTime: ReturnType<typeof vi.fn> };
  let start: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    frequency = { value: 0, setTargetAtTime: vi.fn(), cancelScheduledValues: vi.fn(), setValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn() };
    gain = { value: 0, setTargetAtTime: vi.fn() };
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
      }); }
    });
    vi.stubGlobal("fetch", vi.fn(async () => ({ ok: true, arrayBuffer: async () => new ArrayBuffer(4) })));
    audio = new ForestDrone();
  });
  afterEach(() => { audio.dispose(); vi.useRealTimers(); vi.unstubAllGlobals(); delete (window as unknown as Record<string, unknown>)["AndroidOrientation"]; });

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
    expect(gain.setTargetAtTime).toHaveBeenLastCalledWith(0, 0, 0.05);
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