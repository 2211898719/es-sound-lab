package cn.es.soundlab;

interface EngineRenderer {
  void target(float rate, float gain, float load);
  int render(short[] output, int count);
  float rpm();
}
