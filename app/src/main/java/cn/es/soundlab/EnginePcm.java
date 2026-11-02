package cn.es.soundlab;

import java.io.IOException;

final class EnginePcm {
  static final int OUTPUT_RATE = 48000;
  final float[] left, right;
  final int rate;

  EnginePcm(float[] left, float[] right, int rate) throws IOException {
    if (rate < 8000 || rate > 96000 || left.length < 256 || left.length > rate * 30
        || right != null && right.length != left.length) throw new IOException("声浪素材须为8–96kHz、30秒以内且非空的音频");
    this.left = clean(left);
    this.right = right == null ? this.left : clean(right);
    this.rate = rate;
  }

  private static float[] clean(float[] input) throws IOException {
    double mean = 0;
    for (float sample : input) {
      if (!Float.isFinite(sample) || Math.abs(sample) > 4) throw new IOException("音频包含无效采样");
      mean += sample;
    }
    mean /= input.length;
    float peak = 0;
    for (float sample : input) peak = Math.max(peak, Math.abs(sample - (float) mean));
    float scale = peak > .85f ? .85f / peak : 1;
    float[] result = new float[input.length];
    for (int index = 0; index < input.length; index++) result[index] = (input[index] - (float) mean) * scale;
    return result;
  }

  static final class Render implements EngineRenderer {
    private final EnginePcm pcm;
    private final boolean preview;
    private final int overlap, period, previewFrames;
    private final float smoothing = 1 - (float) Math.exp(-1.0 / (OUTPUT_RATE * .035));
    private final float filter = 1 - (float) Math.exp(-2 * Math.PI * 1000 / OUTPUT_RATE);
    private double position;
    private long frames;
    private float rate = 1, gain, load, filteredLeft, filteredRight;
    volatile float targetRate = 1, targetGain, targetLoad;

    Render(EnginePcm pcm, boolean preview) {
      this.pcm = pcm;
      this.preview = preview;
      overlap = Math.min(pcm.left.length / 4, Math.max(16, pcm.rate * 30 / 1000));
      period = pcm.left.length - overlap;
      previewFrames = (int) (Math.min(5, pcm.left.length / (double) pcm.rate) * OUTPUT_RATE);
    }

    @Override public void target(float rate, float gain, float load) {
      targetRate = finite(rate, .5f, 2);
      targetGain = finite(gain, 0, 1);
      targetLoad = finite(load, 0, 1);
    }

    private static float finite(float value, float min, float max) {
      return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    @Override public int render(short[] output, int count) {
      if (count < 0 || count * 2 > output.length) throw new IllegalArgumentException("PCM缓冲大小错误");
      int rendered = 0;
      for (; rendered < count; rendered++) {
        if (preview && frames >= previewFrames + OUTPUT_RATE / 10) break;
        rate += smoothing * (targetRate - rate);
        gain += smoothing * (targetGain - gain);
        load += smoothing * (targetLoad - load);
        float left = 0, right = 0;
        if (!preview || frames < previewFrames) {
          if (preview) {
            double location = frames * (double) pcm.rate / OUTPUT_RATE;
            left = read(pcm.left, location);
            right = read(pcm.right, location);
          } else {
            left = loop(pcm.left, position);
            right = loop(pcm.right, position);
            position = (position + rate * pcm.rate / OUTPUT_RATE) % period;
          }
        }
        filteredLeft += filter * (left - filteredLeft);
        filteredRight += filter * (right - filteredRight);
        if (!preview) {
          float bright = .25f + .75f * load;
          left = filteredLeft * (1 - bright) + left * bright;
          right = filteredRight * (1 - bright) + right * bright;
        }
        float envelope = Math.min(1, frames / (OUTPUT_RATE * .025f));
        if (preview) envelope *= Math.max(0, Math.min(1, (previewFrames - 1 - frames) / (OUTPUT_RATE * .08f)));
        output[rendered * 2] = sample(left * gain * envelope);
        output[rendered * 2 + 1] = sample(right * gain * envelope);
        frames++;
      }
      return rendered;
    }

    private static short sample(float value) { return (short) Math.round(Math.max(-.98f, Math.min(.98f, value)) * 32767); }

    private float loop(float[] samples, double location) {
      int crossfadeAt = period - overlap;
      if (location < crossfadeAt) return read(samples, location + overlap);
      double blend = (location - crossfadeAt) / overlap;
      float weight = (float) (.5 - .5 * Math.cos(Math.PI * blend));
      return read(samples, location + overlap) * (1 - weight)
          + read(samples, location - crossfadeAt) * weight;
    }

    private static float read(float[] samples, double location) {
      int index = Math.max(0, Math.min(samples.length - 1, (int) location));
      float blend = (float) (location - index);
      return samples[index] + (samples[Math.min(index + 1, samples.length - 1)] - samples[index]) * blend;
    }

    long frames() { return frames; }
    @Override public float rpm() { return rate * 3000; }
    float gain() { return gain; }
  }
}
