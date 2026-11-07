// Continuous background loop served from /audio/, filtered per game state.
const SOURCES = ["/audio/forest-loop.wav", "/audio/forest-loop.mp3"];
// One-shot stings, decoded once per audio context.
export const STINGS = { start: "/audio/run_start.wav", end: "/audio/run_end.wav", threat: "/audio/threat.wav" } as const;
export type Sting = keyof typeof STINGS;
// Start and end stings are dulled with a low-pass (cutoff in Hz); the threat sting stays untouched.
const STING_TONE: Partial<Record<Sting, number>> = { start: 1200, end: 900 };
// Short synthesized cues for moments that were silent (haptics only) until now.
export type Cue = "freezer" | "ghost" | "shockwave" | "ring" | "end" | "stumble" | "gate" | "secret";
// Survive: once the lights die the forest loop sinks to a dull, distant hum.
const MUFFLED_HZ = 380;
const MENU_HZ = 220;
const RUN_HZ = 4000;

// Seamless loop: blend the last 100 ms into the first 100 ms (equal power) and
// drop the tail, so the wrap continues the waveform without a volume dip.
function softenLoopEnds(buffer: AudioBuffer, ctx: BaseAudioContext) {
  const fade = Math.min(Math.floor(buffer.sampleRate * 0.1), buffer.length >> 2);
  if (fade < 2 || typeof ctx.createBuffer !== "function") return buffer;
  const length = buffer.length - fade;
  const out = ctx.createBuffer(buffer.numberOfChannels, length, buffer.sampleRate);
  for (let channel = 0; channel < buffer.numberOfChannels; channel++) {
    const src = buffer.getChannelData(channel);
    const dst = out.getChannelData(channel);
    dst.set(src.subarray(0, length));
    for (let k = 0; k < fade; k++) {
      const t = (k / fade) * Math.PI / 2;
      dst[k] = (src[k] ?? 0) * Math.sin(t) + (src[length + k] ?? 0) * Math.cos(t);
    }
  }
  return out;
}

export class ForestDrone {
  private context: AudioContext | null = null;
  private filter: BiquadFilterNode | null = null;
  private master: GainNode | null = null;
  private source: AudioBufferSourceNode | null = null;
  private stings: Partial<Record<Sting, AudioBuffer>> = {};
  private stingLoads: Partial<Record<Sting, Promise<AudioBuffer | null>>> = {};
  private muffled = false;
  private enabled = true;
  private running = false;
  private resumeTimer: ReturnType<typeof setTimeout> | null = null;
  private volumeTimer: ReturnType<typeof setInterval> | null = null;
  private foreground = true;
  // Last targets handed to the audio graph: apply() runs every 200 ms on Android, and
  // re-issuing the same targets cut every impact dip short and fought its recovery.
  private applied: { context: AudioContext | null; gain: number; hz: number } = { context: null, gain: -1, hz: -1 };

  private canPlay() {
    const native = (globalThis as { AndroidAudio?: { isMuted(): boolean } }).AndroidAudio;
    let muted = false;
    try { muted = native?.isMuted() ?? false; } catch { /* Older native builds. */ }
    return this.enabled && this.foreground && !document.hidden && !muted;
  }

  // Full hardware release: closing the context lets the device power down the
  // speaker amplifier instead of holding the audio channel open at zero gain,
  // which is what made the speaker hiss while muted.
  private teardown() {
    if (this.resumeTimer) clearTimeout(this.resumeTimer);
    this.resumeTimer = null;
    const ctx = this.context;
    if (!ctx) return;
    ctx.onstatechange = null;
    try { this.source?.stop(); } catch { /* Already stopped. */ }
    this.source = null;
    this.context = null;
    this.filter = null;
    this.master = null;
    this.applied = { context: null, gain: -1, hz: -1 };
    this.stings = {};
    this.stingLoads = {};
    void ctx.close().catch(() => undefined);
  }

  private syncOutput() {
    if (!this.canPlay()) { this.teardown(); return; }
    if (!this.context) { this.unlock(); return; }
    this.apply();
    if (this.context.state === "suspended") void this.context.resume().catch(() => undefined);
    this.retryNativeResume();
  }

  unlock() {
    this.foreground = true;
    // The volume poll must run even while muted, so sound returns without a tap.
    if (!this.volumeTimer && "AndroidAudio" in globalThis) {
      this.volumeTimer = setInterval(() => this.syncOutput(), 200);
    }
    if (!this.canPlay()) { this.teardown(); return; }
    try {
      if (!this.context) {
        const ctx = new AudioContext();
        this.context = ctx;
        // Native startup can briefly suspend audio while the window gains focus.
        ctx.onstatechange = () => {
          if (!this.canPlay()) this.syncOutput();
          else this.retryNativeResume();
        };
        this.filter = ctx.createBiquadFilter();
        this.filter.type = "lowpass";
        this.filter.frequency.value = MENU_HZ;
        this.filter.Q.value = 0.4;
        this.master = ctx.createGain();
        this.master.gain.value = 0;
        this.filter.connect(this.master);
        this.master.connect(ctx.destination);
        void this.loadLoop();
        (Object.keys(STINGS) as Sting[]).forEach(name => void this.loadSting(name));
      }
      this.syncOutput();
    } catch { /* Devices without Web Audio stay silent. */ }
  }

  private retryNativeResume() {
    if (this.resumeTimer) clearTimeout(this.resumeTimer);
    this.resumeTimer = null;
    const native = typeof window !== "undefined" && "AndroidOrientation" in window;
    if (!native || !this.canPlay() || this.context?.state !== "suspended") return;
    this.resumeTimer = setTimeout(() => {
      this.resumeTimer = null;
      if (!this.canPlay() || !this.context) return;
      void this.context.resume().catch(() => undefined).finally(() => this.retryNativeResume());
    }, 250);
  }

  private async loadLoop() {
    const ctx = this.context;
    if (!ctx) return;
    for (const url of SOURCES) {
      try {
        const response = await fetch(url);
        if (!response.ok) continue;
        const buffer = softenLoopEnds(await ctx.decodeAudioData(await response.arrayBuffer()), ctx);
        if (ctx !== this.context) return;
        const source = ctx.createBufferSource();
        source.buffer = buffer;
        source.loop = true;
        if (!this.filter) return;
        source.connect(this.filter);
        source.start();
        this.source = source;
        this.syncOutput();
        return;
      } catch { /* Try the next format. */ }
    }
  }

  private loadSting(name: Sting) {
    const ctx = this.context;
    if (!ctx) return Promise.resolve(null);
    const existing = this.stingLoads[name];
    if (existing) return existing;
    const load = (async () => {
      try {
        const response = await fetch(STINGS[name]);
        if (!response.ok) return null;
        const buffer = await ctx.decodeAudioData(await response.arrayBuffer());
        if (ctx !== this.context) return null;
        this.stings[name] = buffer;
        return buffer;
      } catch { return null; /* Stings are optional; the game stays silent without them. */ }
    })();
    this.stingLoads[name] = load;
    return load;
  }

  // One-shot sting. A soft 350 ms fade-out at the tail keeps the ending from
  // clicking against the silence. If the sound is still decoding it plays as
  // soon as it is ready, unless that would arrive noticeably late.
  play(name: Sting) {
    if (!this.context || !this.canPlay()) return;
    const ready = this.stings[name];
    if (ready) { this.startSting(ready, name); return; }
    const asked = Date.now();
    void this.loadSting(name).then(buffer => {
      if (buffer && Date.now() - asked < 700) this.startSting(buffer, name);
    });
  }
  playThreat() { this.play("threat"); }

  private startSting(buffer: AudioBuffer, name: Sting) {
    const ctx = this.context;
    if (!ctx || !this.canPlay()) return;
    try {
      const source = ctx.createBufferSource();
      source.buffer = buffer;
      const gain = ctx.createGain();
      const end = ctx.currentTime + buffer.duration;
      gain.gain.setValueAtTime(0.9, ctx.currentTime);
      gain.gain.setValueAtTime(0.9, Math.max(ctx.currentTime, end - 0.35));
      gain.gain.linearRampToValueAtTime(0, end);
      const tone = STING_TONE[name];
      if (tone) {
        // Muffled start/end: roll the highs off before the fade-out gain.
        const dull = ctx.createBiquadFilter();
        dull.type = "lowpass";
        dull.frequency.value = tone;
        dull.Q.value = 0.5;
        source.connect(dull);
        dull.connect(gain);
      } else {
        source.connect(gain);
      }
      gain.connect(ctx.destination);
      source.start();
    } catch { /* Never let a sting break the run. */ }
  }

  /** Survive mode: dull the forest loop once the lights die. */
  setMuffled(muffled: boolean) {
    if (this.muffled === muffled) return;
    this.muffled = muffled;
    this.apply();
  }

  setMode(enabled: boolean, running: boolean) {
    this.enabled = enabled;
    this.running = running;
    this.syncOutput();
  }

  // 500 ms dip matching the impact glitch: instantly muffled, then back.
  impactDip() {
    const now = this.context?.currentTime;
    if (now == null || !this.filter) return;
    this.filter.frequency.cancelScheduledValues(now);
    this.filter.frequency.setValueAtTime(this.filter.frequency.value, now);
    this.filter.frequency.linearRampToValueAtTime(MENU_HZ, now + 0.03);
    this.filter.frequency.setTargetAtTime(this.running ? (this.muffled ? MUFFLED_HZ : RUN_HZ) : MENU_HZ, now + 0.5, 0.18);
  }

  private apply() {
    const now = this.context?.currentTime;
    if (now == null || !this.master || !this.filter) return;
    if (!this.canPlay()) return;
    const target = this.running ? (this.muffled ? 0.26 : 0.45) : 0.3;
    const hz = this.running ? (this.muffled ? MUFFLED_HZ : RUN_HZ) : MENU_HZ;
    const done = this.applied;
    if (done.context === this.context && done.gain === target && done.hz === hz) return;
    this.applied = { context: this.context, gain: target, hz };
    this.master.gain.setTargetAtTime(target, now, 0.8);
    // A real change of state replaces whatever was still scheduled: a pending impact-dip
    // recovery computed for the old state would otherwise un-muffle the dark forest.
    this.filter.frequency.cancelScheduledValues(now);
    this.filter.frequency.setValueAtTime(this.filter.frequency.value, now);
    this.filter.frequency.setTargetAtTime(hz, now, 0.65);
  }

  private blip(from: number, to: number, seconds: number, peak: number, type: OscillatorType = "sine", delay = 0) {
    const ctx = this.context;
    if (!ctx || !this.canPlay()) return;
    try {
      const t0 = ctx.currentTime + delay;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = type;
      osc.frequency.setValueAtTime(from, t0);
      if (to !== from) osc.frequency.exponentialRampToValueAtTime(Math.max(1, to), t0 + seconds);
      gain.gain.setValueAtTime(0.0001, t0);
      gain.gain.exponentialRampToValueAtTime(peak, t0 + Math.min(0.03, seconds / 4));
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + seconds);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(t0);
      osc.stop(t0 + seconds + 0.05);
    } catch { /* Cues are optional; they must never break a run. */ }
  }

  /** Soft, short feedback for pickups, rings, stumbles, gates and secrets. */
  cue(name: Cue) {
    switch (name) {
      case "freezer": this.blip(660, 330, 0.65, 0.09); this.blip(990, 495, 0.55, 0.05, "triangle", 0.04); break;
      case "ghost": this.blip(220, 440, 0.75, 0.08); this.blip(330, 660, 0.75, 0.045, "sine", 0.08); break;
      case "shockwave": this.blip(110, 40, 0.85, 0.34); this.blip(150, 520, 0.5, 0.08, "triangle", 0.05); break;
      case "ring": this.blip(140, 50, 0.6, 0.24); this.blip(210, 110, 0.4, 0.05, "triangle", 0.02); break;
      case "end": this.blip(300, 150, 0.45, 0.065); break;
      case "stumble": this.blip(95, 52, 0.2, 0.15, "triangle"); break;
      case "gate": this.blip(72, 54, 0.7, 0.11); this.blip(144, 108, 0.6, 0.05, "sine", 0.05); break;
      case "secret":
        this.blip(880, 880, 0.9, 0.055);
        this.blip(1320, 1320, 0.9, 0.04, "sine", 0.12);
        this.blip(1760, 1760, 1.1, 0.03, "sine", 0.26);
        break;
    }
  }

  suspend() { this.foreground = false; this.syncOutput(); }
  dispose() {
    if (this.volumeTimer) clearInterval(this.volumeTimer);
    this.volumeTimer = null;
    this.teardown();
  }
}
