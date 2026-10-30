package cn.es.soundlab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** All artwork is native vector drawing; the UI does not depend on a WebView. */
public final class ArtView extends View {
  private final String type;
  private final int accent;
  private final Paint p = new Paint(3);

  public ArtView(Context c, String t, int a) {
    super(c);
    type = t;
    accent = a;
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  private void fill(int c) {
    p.setShader(null);
    p.setColor(c);
    p.setStyle(Paint.Style.FILL);
  }

  private void stroke(int c, float w) {
    fill(c);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(w);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
  }

  private void round(Canvas c, float x, float y, float w, float h, float r) {
    c.drawRoundRect(new RectF(x, y, x + w, y + h), r, r, p);
  }

  private void path(Canvas c, float... xy) {
    Path path = new Path();
    path.moveTo(xy[0], xy[1]);
    for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
    c.drawPath(path, p);
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    if (type.equals("hero")) {
      hero(c);
      return;
    }
    c.save();
    c.scale(getWidth() / 100f, getHeight() / 100f);
    fill(accent & 0x00ffffff | 0x18000000);
    c.drawCircle(50, 50, 47, p);
    stroke(accent, 5);
    switch (type) {
      case "autohold":
        c.drawCircle(50, 50, 23, p);
        c.drawArc(new RectF(14, 14, 86, 86), 140, 80, false, p);
        c.drawArc(new RectF(14, 14, 86, 86), -40, 80, false, p);
        path(c, 40, 62, 50, 36, 60, 62);
        path(c, 44, 53, 56, 53);
        break;
      case "gear":
        c.save();
        c.rotate(13, 50, 50);
        round(c, 28, 58, 46, 24, 9);
        round(c, 38, 18, 26, 33, 10);
        path(c, 51, 51, 51, 68);
        c.restore();
        break;
      case "lock":
        round(c, 27, 43, 47, 37, 9);
        c.drawArc(new RectF(36, 18, 66, 64), 180, 180, false, p);
        fill(accent);
        c.drawCircle(50, 59, 4, p);
        round(c, 48, 59, 4, 10, 2);
        break;
      case "charge":
        round(c, 25, 15, 39, 69, 9);
        path(c, 64, 33, 79, 33, 79, 62, 72, 62);
        fill(accent);
        path(c, 49, 27, 35, 51, 46, 51, 40, 70, 57, 44, 46, 44, 49, 27);
        break;
      case "shout":
        path(c, 24, 42, 42, 42, 75, 23, 75, 77, 42, 58, 24, 58, 24, 42);
        path(c, 37, 60, 42, 79, 53, 79, 47, 62);
        c.drawArc(new RectF(67, 34, 93, 66), -70, 140, false, p);
        break;
      case "engine":
        round(c, 22, 34, 53, 37, 8);
        path(c, 32, 34, 32, 25, 59, 25, 59, 34);
        path(c, 75, 46, 85, 40, 85, 65, 75, 59);
        path(c, 32, 47, 41, 47, 48, 59, 57, 45, 65, 45);
        break;
      case "pedestrian":
        c.drawCircle(52, 24, 7, p);
        path(c, 51, 37, 43, 57, 58, 69, 65, 85);
        path(c, 43, 57, 34, 80);
        path(c, 49, 42, 66, 51, 76, 47);
        break;
      default:
        car(c, 17, 30, 66, 37, accent);
        if (type.equals("door")) path(c, 47, 50, 69, 38, 69, 72, 47, 65);
        if (type.equals("reverse")) {
          stroke(accent, 3);
          c.drawArc(new RectF(3, 26, 96, 91), 10, 160, false, p);
          c.drawArc(new RectF(9, 34, 90, 83), 10, 160, false, p);
        }
        if (type.equals("tailgate")) path(c, 33, 32, 48, 16, 72, 25);
        break;
    }
    c.restore();
  }

  private void car(Canvas c, float x, float y, float w, float h, int color) {
    fill(color);
    Path body = new Path();
    body.moveTo(x, y + h * .45f);
    body.lineTo(x + w * .19f, y + h * .06f);
    body.lineTo(x + w * .63f, y);
    body.lineTo(x + w * .85f, y + h * .37f);
    body.lineTo(x + w, y + h * .46f);
    body.lineTo(x + w, y + h * .85f);
    body.lineTo(x, y + h * .85f);
    body.close();
    c.drawPath(body, p);
    fill(Color.WHITE);
    path(
        c,
        x + w * .24f,
        y + h * .15f,
        x + w * .58f,
        y + h * .12f,
        x + w * .72f,
        y + h * .39f,
        x + w * .13f,
        y + h * .39f,
        x + w * .24f,
        y + h * .15f);
    fill(Color.rgb(37, 61, 91));
    c.drawCircle(x + w * .21f, y + h * .87f, h * .19f, p);
    c.drawCircle(x + w * .8f, y + h * .87f, h * .19f, p);
    fill(Color.WHITE);
    c.drawCircle(x + w * .21f, y + h * .87f, h * .08f, p);
    c.drawCircle(x + w * .8f, y + h * .87f, h * .08f, p);
  }

  private void hero(Canvas c) {
    float density = getResources().getDisplayMetrics().density,
        w = getWidth() / density,
        h = getHeight() / density;
    c.save();
    c.scale(density, density);
    fill(0xffe6f3ff);
    p.setShader(
        new LinearGradient(
            0,
            0,
            w,
            h,
            new int[] {0xffedf7ff, 0xffddf1fb, 0xffe1f4ee},
            null,
            Shader.TileMode.CLAMP));
    round(c, 0, 0, w, h, 22);
    p.setShader(null);
    fill(0x2471a6c5);
    path(
        c, w * .37f, h * .7f, w * .56f, h * .24f, w * .68f, h * .62f, w * .8f, h * .16f, w, h * .7f,
        w, h, w * .37f, h, w * .37f, h * .7f);
    fill(0x3076b3cd);
    path(
        c, w * .4f, h, w * .6f, h * .47f, w * .7f, h * .78f, w * .9f, h * .38f, w, h * .65f, w, h,
        w * .4f, h);
    fill(0x55ffffff);
    c.drawOval(new RectF(w * .38f, h * .66f, w * 1.15f, h * 1.3f), p);
    if (w > 680) {
      fill(0x18172c4c);
      c.drawOval(new RectF(w - 288, h - 36, w - 64, h - 18), p);
      car(c, w - 280, h - 105, 205, 72, 0xff537b9c);
      stroke(0xff8db7bc, 2);
      path(c, w - 55, h - 24, w - 55, h - 75);
      fill(0xffa9d2c4);
      c.drawOval(new RectF(w - 71, h - 92, w - 39, h - 57), p);
    }
    stroke(0xff377afa, 6);
    for (int i = 0; i < 5; i++) {
      float bar = new float[] {13, 27, 43, 27, 13}[i];
      c.drawLine(30 + i * 9, 41 - bar / 2, 30 + i * 9, 41 + bar / 2, p);
    }
    fill(0xff1b365e);
    p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    p.setTextSize(30);
    c.drawText("音效设置", 90, 51, p);
    p.setTextSize(10);
    p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    c.drawText("E S   S O U N D   L A B", 28, 89, p);
    fill(0xff61758e);
    p.setTextSize(w < 500 ? 13 : 16);
    c.drawText("让每一次出发，都有你的声音", 28, 115, p);
    fill(0xff467a9b);
    p.setTextSize(11);
    c.drawText("星纪元 ES · 专属音效与车辆事件", 28, h - 18, p);
    c.restore();
  }
}
