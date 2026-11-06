/*
 * 秒过 (MiaoGuo) - 开屏广告自动跳过
 * Copyright (C) 2026 MiaoGuo contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.miaoguo.skip;

import android.app.Activity;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity {

    private TextView tvStatusHead;
    private TextView tvStatusSub;
    private TextView tvHeartbeat;
    private TextView tvLog;
    private TextView tvCount;
    private TextView tvToggleSub;
    private TextView btnSettings;
    private TextView btnBattery;
    private TextView btnGuide;
    private View dot;
    private View cardToggle;
    private View svLog;
    private ToggleSwitch toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatusHead = findViewById(R.id.tv_status_head);
        tvStatusSub = findViewById(R.id.tv_status_sub);
        tvHeartbeat = findViewById(R.id.tv_heartbeat);
        tvLog = findViewById(R.id.tv_log);
        tvCount = findViewById(R.id.tv_count);
        tvToggleSub = findViewById(R.id.tv_toggle_sub);
        btnSettings = findViewById(R.id.btn_settings);
        btnBattery = findViewById(R.id.btn_battery);
        btnGuide = findViewById(R.id.btn_guide);
        dot = findViewById(R.id.v_dot);
        cardToggle = findViewById(R.id.card_toggle);
        svLog = findViewById(R.id.sv_log);
        toggle = findViewById(R.id.sw_enabled);

        // 版本号显示出来，排查问题时一眼能确认装的是哪一版
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tv_subtitle)).setText("开屏广告自动跳过 · v" + vn);
        } catch (Throwable ignored) {
        }

        // 第一次打开：先说清楚风险，再进引导页
        if (!Prefs.isDisclaimerSeen(this)) {
            startActivity(new Intent(this, DisclaimerActivity.class));
        } else if (!Prefs.isGuideSeen(this)) {
            startActivity(new Intent(this, GuideActivity.class));
        }

        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });

        btnGuide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, GuideActivity.class));
            }
        });

        findViewById(R.id.btn_disclaimer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DisclaimerActivity.class));
            }
        });

        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBatteryOptimizations();
            }
        });

        toggle.setOnCheckedChangeListener(new ToggleSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(ToggleSwitch view, boolean checked) {
                Prefs.setEnabled(MainActivity.this, checked);
                tvToggleSub.setText(checked ? R.string.toggle_sub_on : R.string.toggle_sub_off);
            }
        });

        // 整张卡片都能点，不用非得点到那个小开关上
        cardToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle.setChecked(!toggle.isChecked(), true);
                Prefs.setEnabled(MainActivity.this, toggle.isChecked());
                tvToggleSub.setText(toggle.isChecked() ? R.string.toggle_sub_on : R.string.toggle_sub_off);
            }
        });

        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.clearLog(MainActivity.this);
                refresh();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    /** 请求把本 App 排除在省电优化之外——不然厂商策略会悄悄把服务冻掉。 */
    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isBatteryWhitelisted() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            // 查不出来就别拿这个烦用户
            return true;
        }
    }

    private void refresh() {
        final boolean serviceOn = isServiceEnabled();
        final boolean masterOn = Prefs.isEnabled(this);

        if (serviceOn) {
            tvStatusHead.setText(R.string.status_on_head);
            tvStatusSub.setText(R.string.status_on_sub);
            dot.setBackgroundResource(R.drawable.dot_on);
            btnSettings.setText(R.string.btn_enabled);
            btnSettings.setBackgroundResource(R.drawable.bg_btn_secondary);
            btnSettings.setTextColor(getResources().getColor(R.color.text));
            tvHeartbeat.setVisibility(View.VISIBLE);
            tvHeartbeat.setText(heartbeatText());
        } else {
            tvStatusHead.setText(R.string.status_off_head);
            tvStatusSub.setText(R.string.status_off_sub);
            dot.setBackgroundResource(R.drawable.dot_off);
            btnSettings.setText(R.string.btn_enable);
            btnSettings.setBackgroundResource(R.drawable.bg_btn_primary);
            btnSettings.setTextColor(getResources().getColor(R.color.accent_ink));
            tvHeartbeat.setVisibility(View.GONE);
        }

        btnBattery.setVisibility(isBatteryWhitelisted() ? View.GONE : View.VISIBLE);

        toggle.setChecked(masterOn, false);
        tvToggleSub.setText(masterOn ? R.string.toggle_sub_on : R.string.toggle_sub_off);

        tvCount.setText(String.valueOf(Prefs.getCount(this)));

        String log = Prefs.getLog(this);
        tvLog.setText(TextUtils.isEmpty(log) ? getString(R.string.log_empty) : log);
        fitLogHeight();
    }

    /**
     * 记录最多显示一屏多，超出就在卡片内部滚动，别把整页拉长。
     * TextView 在 ScrollView 里是按完整内容量高的，所以先量它，再给外层限高。
     */
    private void fitLogHeight() {
        if (svLog == null || tvLog == null) {
            return;
        }
        final float d = getResources().getDisplayMetrics().density;
        final int maxPx = (int) (190 * d);
        final int minPx = (int) (56 * d);
        tvLog.post(new Runnable() {
            @Override
            public void run() {
                int content = tvLog.getHeight();
                if (content <= 0) {
                    return;
                }
                int target = Math.max(minPx, Math.min(content, maxPx));
                ViewGroup.LayoutParams lp = svLog.getLayoutParams();
                if (lp != null && lp.height != target) {
                    lp.height = target;
                    svLog.setLayoutParams(lp);
                }
            }
        });
    }

    /** 用服务最后一次活动的时间，判断它是不是被系统悄悄停掉了。 */
    private String heartbeatText() {
        long beat = Prefs.getHeartbeat(this);
        if (beat <= 0L) {
            return "最近活动：还没有记录";
        }
        long mins = (System.currentTimeMillis() - beat) / 60000L;
        if (mins < 1L) {
            return "最近活动：刚刚";
        }
        if (mins < 60L) {
            return "最近活动：" + mins + " 分钟前";
        }
        if (mins < 60L * 24L) {
            return "最近活动：" + (mins / 60L) + " 小时前";
        }
        return "最近活动：" + (mins / (60L * 24L)) + " 天前 · 可能被系统停掉了，重新开一次无障碍开关";
    }

    /**
     * 无障碍服务是否已启用。
     *
     * 以前只靠读 Settings.Secure 里那串服务名做字符串比较，但系统可能把它存成
     * 「com.miaoguo.skip/.SkipService」这种短形式，跟全名对不上——于是服务明明在跑，
     * 界面却显示「未开启」。现在先问系统要启用列表，那才是权威的；字符串比较只做兜底。
     */
    private boolean isServiceEnabled() {
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> list =
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (list != null) {
                    for (AccessibilityServiceInfo info : list) {
                        if (info == null || info.getResolveInfo() == null
                                || info.getResolveInfo().serviceInfo == null) {
                            continue;
                        }
                        String pkg = info.getResolveInfo().serviceInfo.packageName;
                        String name = info.getResolveInfo().serviceInfo.name;
                        if (getPackageName().equals(pkg) && name != null && name.endsWith("SkipService")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 兜底：读设置里的字符串，两种写法都认
        try {
            String flat = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) {
                return false;
            }
            String pkg = getPackageName();
            return flat.contains(pkg + "/" + SkipService.class.getName())
                    || flat.contains(pkg + "/.SkipService");
        } catch (Throwable t) {
            return false;
        }
    }
}
