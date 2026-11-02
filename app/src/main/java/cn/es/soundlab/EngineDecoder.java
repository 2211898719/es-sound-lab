package cn.es.soundlab;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;

final class EngineDecoder {
  static EnginePcm decode(Context context, String source, BooleanSupplier cancelled) throws Exception {
    if (source.startsWith("asset:")) {
      try (InputStream input = context.getAssets().open(source.substring(6))) {
        EnginePcm pcm = wav(input);
        if (pcm != null) return pcm;
      }
    } else if (source.startsWith("file:")) {
      try (InputStream input = new java.io.FileInputStream(source.substring(5))) {
        EnginePcm pcm = wav(input);
        if (pcm != null) return pcm;
      }
    } else throw new IOException("未知声浪音源");
    return compressed(context, source, cancelled);
  }

  static EnginePcm wav(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] block = new byte[8192];
    int count;
    while ((count = input.read(block)) != -1) {
      if (bytes.size() + count > 20 * 1024 * 1024) throw new IOException("声浪素材超过20MiB");
      bytes.write(block, 0, count);
    }
    byte[] data = bytes.toByteArray();
    if (data.length < 12 || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
        || data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') return null;
    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    int channels = 0, rate = 0, bits = 0, encoding = 0, start = -1, size = 0;
    for (int offset = 12; offset + 8 <= data.length;) {
      int length = buffer.getInt(offset + 4);
      if (length < 0 || (long) offset + 8 + length > data.length) throw new IOException("WAV数据块损坏");
      if (data[offset] == 'f' && data[offset + 1] == 'm' && data[offset + 2] == 't' && length >= 16) {
        encoding = buffer.getShort(offset + 8) & 65535;
        channels = buffer.getShort(offset + 10) & 65535;
        rate = buffer.getInt(offset + 12);
        bits = buffer.getShort(offset + 22) & 65535;
      }
      if (data[offset] == 'd' && data[offset + 1] == 'a' && data[offset + 2] == 't' && data[offset + 3] == 'a') {
        start = offset + 8;
        size = length;
      }
      offset += 8 + length + (length & 1);
    }
    if (encoding != 1 || bits != 16) return null;
    if (channels < 1 || channels > 2 || rate < 8000 || rate > 96000 || start < 0
        || size % (channels * 2) != 0) throw new IOException("WAV声道或采样格式无效");
    int frames = size / (channels * 2);
    if (frames > rate * 30) throw new IOException("请使用30秒以内的循环素材");
    float[] left = new float[frames], right = channels == 2 ? new float[frames] : null;
    buffer.position(start);
    for (int index = 0; index < frames; index++) {
      left[index] = buffer.getShort() / 32768f;
      if (right != null) right[index] = buffer.getShort() / 32768f;
    }
    return new EnginePcm(left, right, rate);
  }

  private static EnginePcm compressed(Context context, String source, BooleanSupplier cancelled) throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec codec = null;
    try {
      if (source.startsWith("file:")) extractor.setDataSource(source.substring(5));
      else try (AssetFileDescriptor asset = context.getAssets().openFd(source.substring(6))) {
        extractor.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
      }
      MediaFormat format = null;
      for (int index = 0; index < extractor.getTrackCount(); index++) {
        MediaFormat candidate = extractor.getTrackFormat(index);
        if (candidate.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
          extractor.selectTrack(index); format = candidate; break;
        }
      }
      if (format == null) throw new IOException("未找到音轨");
      codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
      codec.configure(format, null, null, 0);
      codec.start();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      boolean inputDone = false, outputDone = false;
      int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), encoding = AudioFormat.ENCODING_PCM_16BIT;
      long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
      while (!outputDone) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("声浪加载已取消");
        if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("声浪解码超时");
        if (!inputDone) {
          int index = codec.dequeueInputBuffer(10000);
          if (index >= 0) {
            int length = extractor.readSampleData(codec.getInputBuffer(index), 0);
            inputDone = length < 0;
            codec.queueInputBuffer(index, 0, Math.max(0, length), inputDone ? 0 : extractor.getSampleTime(),
                inputDone ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            if (!inputDone) extractor.advance();
          }
        }
        int index = codec.dequeueOutputBuffer(info, 10000);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat output = codec.getOutputFormat();
          channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          encoding = output.containsKey(MediaFormat.KEY_PCM_ENCODING) ? output.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
        } else if (index >= 0) {
          if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            if (channels < 1 || channels > 2 || rate < 8000 || rate > 96000
                || encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
              throw new IOException("声浪解码格式不受支持");
            int width = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
            if ((long) bytes.size() + info.size > (long) rate * channels * width * 30) throw new IOException("请使用30秒以内的循环素材");
            ByteBuffer output = codec.getOutputBuffer(index);
            output.position(info.offset); output.limit(info.offset + info.size);
            byte[] block = new byte[info.size]; output.get(block); bytes.write(block);
          }
          outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          codec.releaseOutputBuffer(index, false);
        }
      }
      int width = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
      if (channels < 1 || channels > 2 || bytes.size() % (channels * width) != 0) throw new IOException("PCM数据不完整");
      int frames = bytes.size() / channels / width;
      float[] left = new float[frames], right = channels == 2 ? new float[frames] : null;
      ByteBuffer input = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
      for (int index = 0; index < frames; index++) {
        left[index] = width == 4 ? input.getFloat() : input.getShort() / 32768f;
        if (right != null) right[index] = width == 4 ? input.getFloat() : input.getShort() / 32768f;
      }
      return new EnginePcm(left, right, rate);
    } finally {
      if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
      extractor.release();
    }
  }
}
