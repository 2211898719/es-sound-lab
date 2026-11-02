package cn.es.soundlab;

import java.io.IOException;
import java.util.Random;
import java.util.function.BooleanSupplier;

final class EngineSynth {
  private static final Bank[] CACHE = new Bank[3];
  static final int RATE = EnginePcm.OUTPUT_RATE;

  static final class Layer {
    final float[] samples;
    final int cycle;
    final float rpm;
    Layer(float[] samples, int cycle) {
      this.samples = samples;
      this.cycle = cycle;
      rpm = 120f * RATE / cycle;
    }
  }

  static final class Bank {
    final Layer[] layers;
    Bank(Layer[] layers) { this.layers = layers; }
  }

  static synchronized Bank bank(int style, BooleanSupplier cancelled) throws IOException {
    if (style < 0 || style > 2) throw new IOException("未知合成声浪类型");
    check(cancelled);
    if (CACHE[style] != null) return CACHE[style];
    int[] references = style == 1 ? new int[] {950, 2400, 4600, 7200}
        : new int[] {800, 1900, 3500, 5600};
    Layer[] layers = new Layer[7];
    layers[0] = make(style, references[0], 0, cancelled);
    for (int band = 1; band < 4; band++) {
      layers[band * 2 - 1] = make(style, references[band], 1, cancelled);
      layers[band * 2] = make(style, references[band], -1, cancelled);
    }
    check(cancelled);
    return CACHE[style] = new Bank(layers);
  }

  private static void check(BooleanSupplier cancelled) throws IOException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("合成音色加载已取消");
  }

  private static Layer make(int style, int reference, int load, BooleanSupplier cancelled) throws IOException {
    int cycle = Math.round(RATE * 120f / reference);
    int length = cycle * Math.max(2, Math.round(RATE * 1.1f / cycle));
    float[] shape = new float[cycle];
    double period = cycle / (double) RATE;
    int cylinders = style == 1 ? 10 : 8;
    double resonance = style == 1 ? 155 : 92;
    for (int frame = 0; frame < cycle; frame++) {
      if ((frame & 1023) == 0) check(cancelled);
      double phase = frame / (double) cycle, value = 0;
      if (style == 2) {
        value = Math.sin(phase * Math.PI * 16) * .45
            + Math.sin(phase * Math.PI * 24) * .24 + Math.sin(phase * Math.PI * 40) * .09;
      } else {
        for (int cylinder = 0; cylinder < cylinders; cylinder++) {
          double offset = cylinder / (double) cylinders;
          if (style == 0) offset += cylinder % 2 == 0 ? .009 : -.009;
          double age = ((phase - offset + 1) % 1) * period;
          double amplitude = 1 + .17 * Math.cos(cylinder * 2.1) + .09 * Math.sin(cylinder * 3.7);
          double decay = load < 0 ? .0058 : .0036;
          double pulse = Math.exp(-age / decay) / -Math.expm1(-period / decay)
              - Math.exp(-age / .00065) / -Math.expm1(-period / .00065);
          double body = ring(age, period, resonance, .016);
          double edge = ring(age, period, resonance * 3.15, .004);
          value += amplitude * (.72 * pulse + .48 * body + (load > 0 ? .22 : .07) * edge);
        }
        if (load > 0) value += .11 * Math.tanh(value * 2.4);
      }
      shape[frame] = (float) value;
    }
    float[] noise = new float[length];
    Random random = new Random(6173L + style * 971L + reference * 31L + load);
    for (int frame = 0; frame < length; frame++) {
      if ((frame & 1023) == 0) check(cancelled);
      noise[frame] = random.nextFloat() * 2 - 1;
    }
    float[] air = circularLowpass(noise, style == 2 ? 2800 : 2200);
    float[] lowNoise = circularLowpass(noise, 420);
    float[] samples = new float[length];
    float noiseLevel = load > 0 ? .16f : .065f;
    double mean = 0;
    for (int frame = 0; frame < length; frame++) {
      samples[frame] = shape[frame % cycle] + noiseLevel * (air[frame] - lowNoise[frame]);
      mean += samples[frame];
    }
    mean /= length;
    double energy = 0;
    for (int frame = 0; frame < length; frame++) {
      samples[frame] -= (float) mean;
      energy += samples[frame] * samples[frame];
    }
    float target = load > 0 ? .19f : load < 0 ? .12f : .14f;
    float scale = (float) (target / Math.max(.0001, Math.sqrt(energy / length)));
    float peak = 0;
    for (int frame = 0; frame < length; frame++) {
      samples[frame] *= scale;
      peak = Math.max(peak, Math.abs(samples[frame]));
    }
    if (peak > .8f) for (int frame = 0; frame < length; frame++) samples[frame] *= .8f / peak;
    check(cancelled);
    return new Layer(samples, cycle);
  }

  private static double ring(double age, double period, double frequency, double decay) {
    double retention = Math.exp(-period / decay), angle = 2 * Math.PI * frequency;
    return Math.exp(-age / decay) * (Math.sin(angle * age) - retention * Math.sin(angle * (age - period)))
        / (1 - 2 * retention * Math.cos(angle * period) + retention * retention);
  }

  private static float[] circularLowpass(float[] samples, float frequency) {
    float blend = 1 - (float) Math.exp(-2 * Math.PI * frequency / RATE), state = 0;
    for (float value : samples) state += blend * (value - state);
    state /= 1 - (float) Math.pow(1 - blend, samples.length);
    float[] result = new float[samples.length];
    for (int frame = 0; frame < samples.length; frame++) {
      state += blend * (samples[frame] - state);
      result[frame] = state;
    }
    return result;
  }

  static final class Render implements EngineRenderer {
    private final Bank bank;
    private final EngineTuning tuning;
    private final boolean preview;
    private final double[] position = new double[7], alternate = new double[7];
    private final long[] grainAt = new long[7];
    private final int[] fading = new int[7];
    private final float[] weight = new float[7], targetWeight = new float[7];
    private final Random random = new Random(4049);
    private final float slew = 1 - (float) Math.exp(-1.0 / (RATE * .045));
    private float rpm, gain, load, wantedRpm, wantedGain, wantedLoad, filterState, limiter = 1;
    private long frames;

    Render(Bank bank, EngineTuning tuning, boolean preview) {
      this.bank = bank;
      this.tuning = tuning;
      this.preview = preview;
      rpm = wantedRpm = tuning.idle();
      for (int layer = 0; layer < 7; layer++) {
        position[layer] = (layer * 3 % (bank.layers[layer].samples.length / bank.layers[layer].cycle)) * bank.layers[layer].cycle;
        grainAt[layer] = RATE / 2 + random.nextInt(RATE / 2);
      }
    }

    @Override public void target(float rate, float gain, float load) {
      wantedRpm = Float.isFinite(rate) ? EnginePowertrain.clamp(rate * 3000, tuning.idle(), tuning.redline()) : tuning.idle();
      wantedGain = Float.isFinite(gain) ? EnginePowertrain.clamp(gain, 0, 1) : 0;
      wantedLoad = Float.isFinite(load) ? EnginePowertrain.clamp(load, -1, 1) : 0;
    }

    private void weights() {
      java.util.Arrays.fill(targetWeight, 0);
      float mix = EnginePowertrain.clamp((wantedLoad + .5f) / 1.3f, 0, 1);
      mix = mix * mix * (3 - 2 * mix);
      group(1, (float) Math.sin(mix * Math.PI / 2));
      group(2, (float) Math.cos(mix * Math.PI / 2));
      float energy = 0;
      for (float value : targetWeight) energy += value * value;
      if (energy > 1) {
        float scale = 1 / (float) Math.sqrt(energy);
        for (int layer = 0; layer < 7; layer++) targetWeight[layer] *= scale;
      }
    }

    private void group(int first, float scale) {
      int previous = 0;
      for (int layer = first; layer < 7; layer += 2) {
        float low = bank.layers[previous].rpm, high = bank.layers[layer].rpm;
        if (wantedRpm <= high) {
          float blend = EnginePowertrain.clamp((float) (Math.log(wantedRpm / low) / Math.log(high / low)), 0, 1);
          targetWeight[previous] += scale * (float) Math.cos(blend * Math.PI / 2);
          targetWeight[layer] += scale * (float) Math.sin(blend * Math.PI / 2);
          return;
        }
        previous = layer;
      }
      targetWeight[previous] += scale;
    }

    @Override public int render(short[] output, int count) {
      if (count < 0 || count > output.length / 2) throw new IllegalArgumentException("PCM缓冲大小错误");
      weights();
      float brightness = .25f + .35f * Math.max(0, wantedLoad) + .3f * wantedRpm / tuning.redline();
      float filter = 1 - (float) Math.exp(-2 * Math.PI * (700 + 6500 * brightness) / RATE);
      int rendered = 0;
      for (; rendered < count; rendered++) {
        if (preview && frames >= RATE * 51L / 10) break;
        rpm += slew * (wantedRpm - rpm);
        gain += slew * (wantedGain - gain);
        load += slew * (wantedLoad - load);
        float value = 0;
        for (int layer = 0; layer < 7; layer++) {
          Layer voice = bank.layers[layer];
          weight[layer] += slew * (targetWeight[layer] - weight[layer]);
          if (frames >= grainAt[layer] && fading[layer] == 0) {
            alternate[layer] = (position[layer] + random.nextInt(voice.samples.length / voice.cycle) * voice.cycle) % voice.samples.length;
            fading[layer] = RATE / 10;
            grainAt[layer] = frames + RATE * 3 / 4 + random.nextInt(RATE / 2);
          }
          float sample = read(voice.samples, position[layer]);
          double step = rpm / voice.rpm * tuning.tone / 100f;
          position[layer] = (position[layer] + step) % voice.samples.length;
          if (fading[layer] > 0) {
            float blend = 1 - fading[layer] / (RATE / 10f);
            blend = blend * blend * (3 - 2 * blend);
            sample += (read(voice.samples, alternate[layer]) - sample) * blend;
            alternate[layer] = (alternate[layer] + step) % voice.samples.length;
            if (--fading[layer] == 0) position[layer] = alternate[layer];
          }
          value += sample * weight[layer];
        }
        float drive = tuning.intensity / 60f;
        value = value * (1 + drive * .3f) / (1 + drive * Math.abs(value));
        filterState += filter * (value - filterState);
        float envelope = Math.min(1, frames / (RATE * .06f));
        if (preview) envelope *= Math.max(0, Math.min(1, (RATE * 5L - 1 - frames) / (RATE * .16f)));
        float strength = .65f + .35f * tuning.intensity / 100f;
        float raw = filterState * gain * envelope * strength * (.65f + .35f * Math.max(0, load));
        float targetLimit = Math.abs(raw) > .8f ? .8f / Math.abs(raw) : 1;
        limiter += (targetLimit < limiter ? .15f : .00012f) * (targetLimit - limiter);
        short sample = (short) Math.round(EnginePowertrain.clamp(raw * limiter, -.97f, .97f) * 32767);
        output[rendered * 2] = sample;
        output[rendered * 2 + 1] = sample;
        frames++;
      }
      return rendered;
    }

    private static float read(float[] samples, double position) {
      int index = (int) position;
      float blend = (float) (position - index);
      return samples[index] + (samples[(index + 1) % samples.length] - samples[index]) * blend;
    }

    @Override public float rpm() { return rpm; }
    long frames() { return frames; }
  }
}
