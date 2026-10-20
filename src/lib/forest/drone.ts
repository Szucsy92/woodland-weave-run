// Continuous background loop served from /audio/, filtered per game state.
const SOURCES = ["/audio/forest-loop.wav", "/audio/forest-loop.mp3"];
const MENU_HZ = 700;
const RUN_HZ = 4000;

// Removes the click at the loop point: fade both ends to silence (30 ms).
function softenLoopEnds(buffer: AudioBuffer) {
  const edge = Math.min(Math.floor(buffer.sampleRate * 0.03), (buffer.length - 1) >> 1);
  for (let channel = 0; channel < buffer.numberOfChannels; channel++) {
    const data = buffer.getChannelData(channel);
    for (let k = 0; k < edge; k++) {
      const g = k / edge;
      data[k] = (data[k] ?? 0) * g;
      data[data.length - 1 - k] = (data[data.length - 1 - k] ?? 0) * g;
    }
  }
  return buffer;
}

export class ForestDrone {
  private context: AudioContext | null = null;
  private filter: BiquadFilterNode | null = null;
  private master: GainNode | null = null;
  private source: AudioBufferSourceNode | null = null;
  private enabled = true;
  private running = false;

  unlock() {
    try {
      if (!this.context) {
        const ctx = new AudioContext();
        this.context = ctx;
        this.filter = ctx.createBiquadFilter();
        this.filter.type = "lowpass";
        this.filter.frequency.value = MENU_HZ;
        this.filter.Q.value = 0.4;
        this.master = ctx.createGain();
        this.master.gain.value = 0;
        this.filter.connect(this.master);
        this.master.connect(ctx.destination);
        void this.loadLoop();
      }
      void this.context.resume().catch(() => undefined);
      this.apply();
    } catch { /* Devices without Web Audio stay silent. */ }
  }

  private async loadLoop() {
    const ctx = this.context;
    if (!ctx) return;
    for (const url of SOURCES) {
      try {
        const response = await fetch(url);
        if (!response.ok) continue;
        const buffer = softenLoopEnds(await ctx.decodeAudioData(await response.arrayBuffer()));
        if (ctx !== this.context) return;
        const source = ctx.createBufferSource();
        source.buffer = buffer;
        source.loop = true;
        source.connect(this.filter as BiquadFilterNode);
        source.start();
        this.source = source;
        this.apply();
        return;
      } catch { /* Try the next format. */ }
    }
  }

  setMode(enabled: boolean, running: boolean) {
    this.enabled = enabled;
    this.running = running;
    this.apply();
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
    const target = this.enabled ? (this.running ? 0.45 : 0.3) : 0;
    this.master.gain.setTargetAtTime(target, now, this.enabled ? 0.8 : 0.05);
    this.filter.frequency.setTargetAtTime(this.running ? RUN_HZ : MENU_HZ, now, 0.65);
  }

  suspend() { void this.context?.suspend().catch(() => undefined); }
  dispose() {
    this.source?.stop();
    this.source = null;
    void this.context?.close().catch(() => undefined);
    this.context = null;
  }
}
