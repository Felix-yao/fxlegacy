package com.r1.controller;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class Tc1Activity extends Activity implements Tc1Client.Listener {

    /** Device MAC; filled from DeviceConfig (saved value → auto discovery). */
    public static String TC1_MAC = "";

    private Tc1Client client;
    private Tc1Client.Listener clientListener;
    private final Handler ui = new Handler();

    private TextView tcStatus, powerText, runtimeText, masterSwitch;
    private TextView tcMac, tcVersion;
    private LinearLayout plugRowTop, plugRowBottom;

    private final TextView[] plugNameViews = new TextView[6];
    private final TextView[] plugSwitchViews = new TextView[6];
    private final TextView[] plugTimerViews = new TextView[6];
    private final boolean[] plugOn = new boolean[6];
    private final String[] plugNames = {"上右", "上中", "上左", "下右", "下中", "下左"};

    // independent countdown per socket (0 = no timer)
    private final long[] timerEndAt = new long[6];

    private boolean masterOn = false;
    private boolean lastReplySeen = false;

    /** One ticking loop updates all six socket countdowns. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            boolean any = false;
            for (int i = 0; i < 6; i++) {
                long left = timerEndAt[i] - now;
                if (timerEndAt[i] > 0 && left <= 0) {
                    // time's up: turn THIS socket off
                    timerEndAt[i] = 0;
                    plugOn[i] = false;
                    plugSwitchViews[i].setBackgroundResource(R.drawable.switch_off);
                    client.setPlug(i, false);
                    plugTimerViews[i].setText("定时");
                    plugTimerViews[i].setBackgroundResource(R.drawable.pill);
                    plugTimerViews[i].setTextColor(0xFF007AFF);
                    Toast.makeText(Tc1Activity.this, plugNames[i] + " 定时到点，已关闭", Toast.LENGTH_SHORT).show();
                } else if (timerEndAt[i] > 0) {
                    long sec = left / 1000;
                    plugTimerViews[i].setText(String.format("%02d:%02d", sec / 60, sec % 60));
                    any = true;
                }
            }
            if (any) ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tc1);
        setTitle("TC1 排插");
        findViewById(R.id.tc1Back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        tcStatus = findViewById(R.id.tc1Status);
        powerText = findViewById(R.id.powerText);
        runtimeText = findViewById(R.id.runtimeText);
        masterSwitch = findViewById(R.id.masterSwitch);
        tcMac = findViewById(R.id.tcMac);
        tcVersion = findViewById(R.id.tcVersion);
        plugRowTop = findViewById(R.id.plugRowTop);
        plugRowBottom = findViewById(R.id.plugRowBottom);

        TC1_MAC = DeviceConfig.tc1Mac(this);
        tcMac.setText(TC1_MAC);
        buildPlugRows();

        masterSwitch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                masterOn = !masterOn;
                masterSwitch.setBackgroundResource(masterOn ? R.drawable.switch_on : R.drawable.switch_off);
                client.setAll(masterOn);
            }
        });

        client = new Tc1Client(TC1_MAC);
        clientListener = this;
        client.addListener(clientListener);
        client.start();
        client.query();
        ui.postDelayed(refreshLoop, 5000);

        // Never confirmed a device before? Ask the LAN who is out there and
        // adopt the MAC it reports.
        if (!DeviceConfig.tc1Verified(this)) discoverAndUseMac();
    }

    /** Best-effort discovery; silently keeps the configured MAC if nothing answers. */
    private void discoverAndUseMac() {
        new Thread(new Runnable() {
            @Override public void run() {
                final String mac = DeviceConfig.discoverTc1Mac(1800);
                if (mac == null || mac.equals(TC1_MAC)) return;
                TC1_MAC = mac;
                DeviceConfig.setTc1Mac(Tc1Activity.this, mac);
                ui.post(new Runnable() {
                    @Override public void run() {
                        tcMac.setText(mac);
                        if (client != null) client.removeListener(clientListener);
                        client = new Tc1Client(mac);
                        client.addListener(clientListener);
                        client.start();
                        client.query();
                    }
                });
            }
        }, "tc1-discover").start();
    }

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            client.query();
            ui.postDelayed(this, 5000);
        }
    };

    private void buildPlugRows() {
        LayoutInflater inf = LayoutInflater.from(this);
        // Physical arrangement of the strip:
        //   top row (L,M,R) = plug_2, plug_1, plug_0
        //   bottom row (L,M,R) = plug_5, plug_4, plug_3
        int[][] order = {{2, 1, 0}, {5, 4, 3}};
        LinearLayout[] rows = {plugRowTop, plugRowBottom};

        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 3; c++) {
                final int idx = order[r][c];
                View cell = inf.inflate(R.layout.plug_cell, rows[r], false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                int m = dp(4);
                lp.setMargins(c == 0 ? 0 : m, 0, c == 2 ? 0 : m, 0);
                cell.setLayoutParams(lp);

                plugNameViews[idx] = cell.findViewById(R.id.plugName);
                plugTimerViews[idx] = cell.findViewById(R.id.plugTimer);
                plugSwitchViews[idx] = cell.findViewById(R.id.plugSwitch);
                plugNameViews[idx].setText(plugNames[idx]);

                plugSwitchViews[idx].setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        plugOn[idx] = !plugOn[idx];
                        plugSwitchViews[idx].setBackgroundResource(plugOn[idx] ? R.drawable.switch_on : R.drawable.switch_off);
                        client.setPlug(idx, plugOn[idx]);
                    }
                });
                plugNameViews[idx].setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showRenameDialog(idx); }
                });
                plugTimerViews[idx].setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showTimerDialog(idx); }
                });
                rows[r].addView(cell);
            }
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** iOS-style per-socket timer chooser. */
    private void showTimerDialog(final int idx) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_timer);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.findViewById(R.id.dlgCard).setClipToOutline(true); // round ALL corners, not just top
        ((TextView) d.findViewById(R.id.dlgTitle)).setText(plugNames[idx] + " · 定时关闭");
        final TextView cancelTimer = (TextView) d.findViewById(R.id.dlgCancelTimer);
        if (timerEndAt[idx] <= 0) cancelTimer.setVisibility(View.GONE);

        d.findViewById(R.id.dlg5).setOnClickListener(timerPick(d, idx, 5));
        d.findViewById(R.id.dlg15).setOnClickListener(timerPick(d, idx, 15));
        d.findViewById(R.id.dlg30).setOnClickListener(timerPick(d, idx, 30));
        d.findViewById(R.id.dlg60).setOnClickListener(timerPick(d, idx, 60));
        d.findViewById(R.id.dlgClose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        cancelTimer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                timerEndAt[idx] = 0;
                plugTimerViews[idx].setText("定时");
                plugTimerViews[idx].setBackgroundResource(R.drawable.pill);
                plugTimerViews[idx].setTextColor(0xFF007AFF);
                Toast.makeText(Tc1Activity.this, plugNames[idx] + " 定时已取消", Toast.LENGTH_SHORT).show();
                d.dismiss();
            }
        });
        d.show();
    }

    private View.OnClickListener timerPick(final Dialog d, final int idx, final int minutes) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                timerEndAt[idx] = System.currentTimeMillis() + minutes * 60 * 1000L;
                plugTimerViews[idx].setBackgroundResource(R.drawable.pill_active);
                plugTimerViews[idx].setTextColor(0xFFFFFFFF);
                Toast.makeText(Tc1Activity.this, plugNames[idx] + " " + minutes + " 分钟后关闭", Toast.LENGTH_SHORT).show();
                ui.removeCallbacks(tick);
                ui.post(tick);
                d.dismiss();
            }
        };
    }

    private void showRenameDialog(final int idx) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_rename);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.findViewById(R.id.rngCard).setClipToOutline(true);
        ((TextView) d.findViewById(R.id.rngTitle)).setText("重命名插口 " + (idx + 1));
        final EditText et = (EditText) d.findViewById(R.id.rngInput);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(plugNames[idx]);
        et.setSelection(et.getText().length());

        d.findViewById(R.id.rngCancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        d.findViewById(R.id.rngSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = et.getText().toString().trim();
                if (name.length() == 0) return;
                plugNames[idx] = name;
                plugNameViews[idx].setText(name);
                client.renamePlug(idx, name);
                d.dismiss();
            }
        });
        d.show();
    }

    private String fmtRuntime(int sec) {
        int d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("天");
        if (h > 0) sb.append(h).append("小时");
        if (m > 0) sb.append(m).append("分");
        if (sb.length() == 0) sb.append("<1分");
        return sb.toString();
    }

    @Override public void onState(final Tc1Client.Tc1State s) {
        ui.post(new Runnable() {
            @Override public void run() {
                lastReplySeen = true;
                tcStatus.setText("在线 · " + s.mac);
                tcStatus.setTextColor(0xFF34C759);
                if (s.hasPlugData && s.mac != null && s.mac.length() == 12
                        && !DeviceConfig.tc1Verified(Tc1Activity.this)) {
                    DeviceConfig.setTc1Verified(Tc1Activity.this, true);
                }
                if (s.power != null) {
                    powerText.setText(s.power.equals("-1") ? "异常" : s.power + " W");
                }
                if (s.hasSensorData) runtimeText.setText(fmtRuntime(s.totalTime));
                if (s.version != null) tcVersion.setText(s.version);
                if (!s.hasPlugData) return;
                boolean anyOn = false, allOn = true;
                for (int i = 0; i < 6; i++) {
                    if (s.on[i]) { plugOn[i] = true; anyOn = true; }
                    else { plugOn[i] = false; allOn = false; }
                    plugSwitchViews[i].setBackgroundResource(plugOn[i] ? R.drawable.switch_on : R.drawable.switch_off);
                    if (s.plugName[i] != null && !s.plugName[i].equals(plugNames[i])) {
                        plugNames[i] = s.plugName[i];
                        plugNameViews[i].setText(plugNames[i]);
                    }
                }
                masterOn = allOn && anyOn;
                masterSwitch.setBackgroundResource(masterOn ? R.drawable.switch_on : R.drawable.switch_off);
            }
        });
    }

    @Override public void onError(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (!lastReplySeen) { tcStatus.setText("未检测到设备");
                    tcStatus.setTextColor(0xFFE24B4A); }
            }
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(refreshLoop);
        ui.removeCallbacks(tick);
        if (client != null && clientListener != null) client.detach(clientListener);
    }
}
