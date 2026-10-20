// One continuous sound, filtered between menu/death and the waking run.
export class ForestDrone {
  private context: AudioContext | null = null;
  private master: GainNode | null = null;
  private filter: BiquadFilterNode | null = null;
  private nodes: OscillatorNode[] = [];
  private enabled = true;
  private running = false;

  unlock() {
    try {
      if (!this.context) {
        this.context = new AudioContext();
        const ctx = this.context;
        this.master = ctx.createGain();
        this.master.gain.value = 0;
        this.filter = ctx.createBiquadFilter();
        this.filter.type = "lowpass";
        this.filter.frequency.value = 190;
        this.filter.Q.value = 0.3;
        this.filter.connect(this.master);
        this.master.connect(ctx.destination);
        [48, 48.27, 72.05, 96.12, 144.3].forEach((frequency, index) => {
          const osc = ctx.createOscillator(), gain = ctx.createGain();
          osc.type = "sine";
          osc.frequency.value = frequency;
          gain.gain.value = [0.36, 0.28, 0.12, 0.07, 0.025][index] ?? 0.025;
          osc.connect(gain); gain.connect(this.filter as BiquadFilterNode);
          osc.start(); this.nodes.push(osc);
        });
        const lfo = ctx.createOscillator(), depth = ctx.createGain();
        lfo.frequency.value = 0.075; depth.gain.value = 24;
        lfo.connect(depth); depth.connect(this.filter.frequency); lfo.start(); this.nodes.push(lfo);
      }
      void this.context.resume().catch(() => undefined);
      this.update();
    } catch { /* Devices without Web Audio can still play silently. */ }
  }

  setMode(enabled: boolean, running: boolean) {
    this.enabled = enabled; this.running = running; this.update();
  }

  private update() {
    if (!this.context || !this.master || !this.filter) return;
    const now = this.context.currentTime;
    this.master.gain.setTargetAtTime(this.enabled ? (this.running ? 0.16 : 0.052) : 0, now, this.enabled ? 0.65 : 0.035);
    this.filter.frequency.setTargetAtTime(this.running ? 1100 : 190, now, 0.65);
  }

  suspend() { void this.context?.suspend().catch(() => undefined); }
  dispose() { this.nodes.forEach(n => n.stop()); this.nodes = []; void this.context?.close(); this.context = null; }
}