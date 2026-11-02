package cn.es.soundlab;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public final class StateDashboardView extends LinearLayout {
  public interface Listener { void calibrate(String key); }

  private static final class Tile {
    LinearLayout box;
    TextView value, raw, detail, calibration, speedCandidates;
  }

  private final UiTheme theme;
  private final Map<String, Tile> tiles = new LinkedHashMap<>();

  public StateDashboardView(Context context, Listener listener) {
    super(context);
    theme = new UiTheme(context);
    setOrientation(VERTICAL);
    int width = context.getResources().getConfiguration().screenWidthDp;
    int columns = width >= 1000 ? 4 : width >= 700 ? 3 : 2;
    boolean logLockSelected = new Store(context).logLockSelected();
    for (boolean interfaceGroup : new boolean[] {true, false}) {
      TextView heading = label(interfaceGroup ? "ICC 接口 · 无需日志授权" : "授权日志 · 用户自行启用", 17, theme.text);
      heading.setTypeface(null, Typeface.BOLD);
      heading.setPadding(0, dp(12), 0, dp(12));
      addView(heading);
      List<VehicleStateSpec> specs = new ArrayList<>();
      for (VehicleStateSpec spec : VehicleStateSpec.ALL)
        if ((VehicleStateSpec.interfaceKey(spec.key)
            && !(spec.key.equals("lock") && logLockSelected)) == interfaceGroup) specs.add(spec);
      LinearLayout row = null;
      for (int index = 0; index < specs.size(); index++) {
        VehicleStateSpec spec = specs.get(index);
        if (index % columns == 0) {
          row = new LinearLayout(context);
          row.setOrientation(HORIZONTAL);
          LayoutParams params = new LayoutParams(-1, -2);
          params.bottomMargin = dp(10);
          addView(row, params);
        }
        Tile tile = new Tile();
        tile.box = new LinearLayout(context);
        tile.box.setOrientation(VERTICAL);
        tile.box.setPadding(dp(14), dp(12), dp(14), dp(12));
        tile.box.setMinimumHeight(dp(180));
        tile.box.setContentDescription("校准" + spec.title);
        tile.box.setOnClickListener(view -> listener.calibrate(spec.key));
        tile.box.setFocusable(true);
        TextView title = label(spec.title, 16, theme.text);
        title.setTypeface(null, Typeface.BOLD);
        tile.box.addView(title);
        tile.value = label("未收到", 30, theme.muted);
        tile.value.setTypeface(null, Typeface.BOLD);
        tile.value.setTag("state.value." + spec.key);
        tile.value.setPadding(0, dp(6), 0, dp(6));
        tile.box.addView(tile.value);
        tile.raw = label("原始值：—", 12, theme.secondaryText);
        tile.raw.setTag("state.raw." + spec.key);
        tile.raw.setMaxLines(2);
        tile.box.addView(tile.raw);
        if (spec.key.equals("speed")) {
          tile.speedCandidates = label(IccSpeedReading.display(null, false), 16, theme.secondaryText);
          tile.speedCandidates.setTag("state.speed.candidates");
          tile.speedCandidates.setPadding(0, dp(8), 0, dp(5));
          tile.box.addView(tile.speedCandidates);
        }
        tile.detail = label("等待本次监听数据", 11, theme.muted);
        tile.detail.setTag("state.detail." + spec.key);
        tile.box.addView(tile.detail);
        TextView events = label(spec.events, 11, theme.muted);
        events.setPadding(0, dp(5), 0, 0);
        tile.box.addView(events);
        tile.calibration = label("点击校准", 12, theme.accent);
        tile.calibration.setPadding(0, dp(5), 0, 0);
        tile.calibration.setTag("state.calibration." + spec.key);
        tile.box.addView(tile.calibration);
        LayoutParams params = new LayoutParams(0, -2, 1);
        if (index % columns != columns - 1) params.rightMargin = dp(10);
        row.addView(tile.box, params);
        tiles.put(spec.key, tile);
      }
      int remainder = specs.size() % columns;
      if (remainder != 0) for (int index = remainder; index < columns; index++)
        row.addView(new LinearLayout(context), new LayoutParams(0, 1, 1));
    }
  }

  public void update(Diagnostics diagnostics, Store store, boolean running, long now) {
    Map<String, Diagnostics.StateReading> readings = diagnostics.stateReadings();
    Map<String, Diagnostics.Live> live = diagnostics.live();
    for (VehicleStateSpec spec : VehicleStateSpec.ALL) {
      Tile tile = tiles.get(spec.key);
      Diagnostics.StateReading reading = readings.get(spec.key);
      if (tile.speedCandidates != null)
        tile.speedCandidates.setText(IccSpeedReading.display(null, false));
      int color = theme.muted, fill = theme.neutralFill;
      boolean logLock = spec.key.equals("lock") && store.logLockSelected();
      if (reading != null && IccVehicleWire.state(reading.sample) && !logLock) {
        Protocol.Sample sample = reading.sample;
        Diagnostics.Live state = live.get(spec.key);
        boolean active = running && state != null && state.source.equals("ICC");
        boolean fresh = active && now - reading.receivedAt <= 5000 && now - sample.time <= 5000
            && sample.time <= now + 500;
        if (tile.speedCandidates != null) {
          boolean freshSpeed = IccSpeedReading.fresh(sample, active, reading.receivedAt, now);
          tile.speedCandidates.setText(IccSpeedReading.display(sample, freshSpeed));
          tile.speedCandidates.setTextColor(freshSpeed ? theme.secondaryText : theme.muted);
        }
        String label = IccVehicleWire.stateLabel(sample);
        if (spec.key.equals("lock") && sample.value != null && !store.iccTriggerConfirmed("lock")) label = "协议·" + label;
        tile.value.setText(active ? label : "待新样本");
        tile.raw.setText("原始值：" + sample.raw + (spec.key.equals("speed") ? " · km/h（本车已核对）" : " · ICC协议"));
        tile.detail.setText((fresh ? "" : "上次状态 · ") + Math.max(0, (now - reading.receivedAt) / 1000)
            + "秒前回复 · ICC\n收到 " + reading.count + " 次 / 原值变化 " + reading.changes + " 次");
        if (spec.key.equals("lock")) tile.calibration.setText("P挡后锁变化 · 默认原厂车外通道");
        else if (spec.key.equals("charge"))
          tile.calibration.setText("点击核对 · " + (store.iccTriggerConfirmed(spec.key) ? "本来源触发已确认" : "仅展示，触发未确认"));
        else tile.calibration.setText(spec.key.equals("gear") ? "ICC协议挡位 · 不套用日志预设"
            : spec.key.equals("speed") ? "原值即km/h · 不参与自动循环音"
            : spec.key.equals("handbrake") ? "ICC手刹 · 不作为AutoHold" : "ICC协议只读 · 无音效触发");
        boolean known = sample.value != null;
        boolean pending = spec.key.equals("lock") && !store.iccTriggerConfirmed("lock");
        color = !fresh ? theme.muted : !known || pending ? theme.warning : theme.good;
        fill = !fresh ? theme.neutralFill : !known || pending ? theme.warningFill : theme.goodFill;
      } else if (VehicleStateSpec.interfaceKey(spec.key) && !logLock) {
        tile.value.setText("未收到");
        tile.raw.setText("原始值：—");
        tile.detail.setText("ICC接口 · 等待本次数据，不从日志补位");
        tile.calibration.setText(spec.key.equals("lock") ? "P挡后锁变化 · 无需二次确认" : spec.key.equals("charge")
            ? "点击核对 · 本来源触发需确认" : "ICC固定协议 · 不套用日志预设");
      } else {
        boolean calibrated = spec.key.equals("lock") ? store.lockCalibrated()
            : spec.key.equals("autohold") ? store.autoHoldCalibrated()
            : store.stateCalibration(spec.key).length() != 0;
        tile.calibration.setText(reading != null && IccVehicleWire.gear(reading.sample) ? "ICC协议挡位 · 不套用日志预设" : reading != null && CarotaLog.gear(reading.sample) ? "Carota命名挡位 · P/R/D" : "点击校准 · " + (store.esStatePreset(spec.key) ? "ES预设 · 待核对" : calibrated ? "已有校准" :
            spec.key.equals("lock") || spec.key.equals("autohold") ? "需确认含义" : "默认映射"));
        if (reading == null || logLock && !reading.sample.source.equals("日志")) {
          tile.value.setText(store.logsEnabled() ? "未收到" : "日志未启用");
          tile.raw.setText("原始值：—");
          tile.detail.setText(store.logsEnabled() ? "授权日志 · 等待本次事件" : "在来源设置中启用并自行授权");
        } else {
          Protocol.Sample sample = reading.sample;
          long age = Math.max(0, (now - reading.receivedAt) / 1000);
          Diagnostics.Live state = live.get(spec.key);
          boolean active = running && store.logsEnabled() && state != null && state.source.equals(sample.source);
          String raw = sample.raw.length() > 48 ? sample.raw.substring(0, 48) + "…" : sample.raw;
          tile.raw.setText("原始值：" + raw + (CarotaLog.gear(sample) ? " · 命名字段" : " · status=" + sample.status));
          tile.detail.setText(age + "秒前收到 · " + (CarotaLog.gear(sample) ? "Carota文件" : sample.source) + "\n收到 " + reading.count
              + " 次 / 原值变化 " + reading.changes + " 次");
          if (!active) tile.value.setText("待新样本");
          else if (sample.value == null) {
            tile.value.setText(sample.status != 0 ? "无效状态" :
                (spec.key.equals("lock") || spec.key.equals("autohold")) && !calibrated ? "未校准" : "未映射");
            color = theme.warning;
            fill = theme.warningFill;
          } else {
            boolean fresh = now - reading.receivedAt <= 5000 && now - sample.time <= 5000
                && sample.time <= now + 500;
            tile.value.setText(spec.label(sample.value));
            color = fresh ? theme.good : theme.muted;
            fill = fresh ? theme.goodFill : theme.neutralFill;
            if (!fresh) tile.detail.setText("上次状态 · " + tile.detail.getText());
          }
        }
      }
      tile.value.setTextColor(color);
      GradientDrawable background = new GradientDrawable();
      background.setColor(fill);
      background.setCornerRadius(dp(14));
      background.setStroke(dp(1), theme.border);
      tile.box.setBackground(background);
    }
  }

  private TextView label(String value, int size, int color) {
    TextView view = new TextView(getContext());
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setGravity(Gravity.START);
    return view;
  }

  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
