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

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 配置与记录的唯一存取点。 */
public final class Prefs {

    private static final String FILE = "miaoguo";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LOG = "log";
    private static final String KEY_COUNT = "count";
    private static final String KEY_GUIDE = "guide_seen";
    private static final String KEY_DISCLAIMER = "disclaimer_seen";
    private static final String KEY_BEAT = "heartbeat";
    private static final int LOG_MAX_LINES = 60;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public static boolean isGuideSeen(Context c) {
        return sp(c).getBoolean(KEY_GUIDE, false);
    }

    public static void setGuideSeen(Context c) {
        sp(c).edit().putBoolean(KEY_GUIDE, true).apply();
    }

    public static boolean isDisclaimerSeen(Context c) {
        return sp(c).getBoolean(KEY_DISCLAIMER, false);
    }

    public static void setDisclaimerSeen(Context c) {
        sp(c).edit().putBoolean(KEY_DISCLAIMER, true).apply();
    }

    /** 服务还活着的证据。系统杀掉它之后这个时间就不再更新。 */
    public static long getHeartbeat(Context c) {
        return sp(c).getLong(KEY_BEAT, 0L);
    }

    public static void beat(Context c) {
        sp(c).edit().putLong(KEY_BEAT, System.currentTimeMillis()).apply();
    }

    public static long getCount(Context c) {
        return sp(c).getLong(KEY_COUNT, 0L);
    }

    public static String getLog(Context c) {
        return sp(c).getString(KEY_LOG, "");
    }

    public static void clearLog(Context c) {
        sp(c).edit().putString(KEY_LOG, "").putLong(KEY_COUNT, 0L).apply();
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date());
    }

    /** 往记录里加一行，只保留最近 LOG_MAX_LINES 行。 */
    private static void append(SharedPreferences p, String entry) {
        String old = p.getString(KEY_LOG, "");
        String merged = old == null || old.isEmpty() ? entry : entry + "\n" + old;
        String[] lines = merged.split("\n");
        if (lines.length > LOG_MAX_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < LOG_MAX_LINES; i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(lines[i]);
            }
            merged = sb.toString();
        }
        p.edit().putString(KEY_LOG, merged).apply();
    }

    /** 记录一次成功跳过：写一行日志，并把累计数加一。 */
    public static synchronized void record(Context c, String appName, String label) {
        SharedPreferences p = sp(c);
        append(p, stamp() + "  ✓ " + appName + " · " + label);
        p.edit()
                .putLong(KEY_COUNT, p.getLong(KEY_COUNT, 0L) + 1L)
                .putLong(KEY_BEAT, System.currentTimeMillis())
                .apply();
    }

    /** 记一条排查信息（不算成功跳过，用于定位"点了没用"的 App）。 */
    public static synchronized void note(Context c, String text) {
        append(sp(c), stamp() + "  ⚠ " + text);
    }
}
