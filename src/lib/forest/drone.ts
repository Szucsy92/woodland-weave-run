// Continuous background loop served from /audio/, filtered per game state.
const SOURCES = ["/audio/forest-loop.wav", "/audio/forest-loop.mp3"];
const THREAT_URL = "/audio/threat.mp3";
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
  private threatBuffer: AudioBuffer | null = null;
  private enabled = true;
  private running = false;
  private resumeTimer: ReturnType<typeof setTimeout> | null = null;
  private volumeTimer: ReturnType<typeof setInterval> | null = null;
  private foreground = true;

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
        void this.loadThreat();
      }
      if (!this.volumeTimer && "AndroidAudio" in globalThis) {
        this.volumeTimer = setInterval(() => this.syncOutput(), 200);
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

  private async loadThreat() {
    const ctx = this.context;
    if (!ctx) return;
    try {
      const response = await fetch(THREAT_URL);
      if (!response.ok) return;
      const buffer = await ctx.decodeAudioData(await response.arrayBuffer());
      if (ctx === this.context) this.threatBuffer = buffer;
    } catch { /* The sting is optional; the game stays silent without it. */ }
  }

  // One-shot threat sting. A soft 350 ms fade-out at the tail keeps the ending
  // from clicking against the silence.
  playThreat() {
    const ctx = this.context;
    const buffer = this.threatBuffer;
    if (!ctx || !buffer || !this.canPlay()) return;
    try {
      const source = ctx.createBufferSource();
      source.buffer = buffer;
      const gain = ctx.createGain();
      const end = ctx.currentTime + buffer.duration;
      gain.gain.setValueAtTime(0.9, ctx.currentTime);
      gain.gain.setValueAtTime(0.9, Math.max(ctx.currentTime, end - 0.35));
      gain.gain.linearRampToValueAtTime(0, end);
      source.connect(gain);
      gain.connect(ctx.destination);
      source.start();
    } catch { /* Never let a sting break the run. */ }
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
    this.filter.frequency.setTargetAtTime(this.running ? RUN_HZ : MENU_HZ, now + 0.5, 0.18);
  }

  private apply() {
    const now = this.context?.currentTime;
    if (now == null || !this.master || !this.filter) return;
    if (!this.canPlay()) return;
    const target = this.running ? 0.45 : 0.3;
    this.master.gain.setTargetAtTime(target, now, 0.8);
    this.filter.frequency.setTargetAtTime(this.running ? RUN_HZ : MENU_HZ, now, 0.65);
  }

  suspend() { this.foreground = false; this.syncOutput(); }
  dispose() {
    if (this.volumeTimer) clearInterval(this.volumeTimer);
    this.volumeTimer = null;
    this.teardown();
  }
}
