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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 开屏广告自动跳过（精确优先版）。
 *
 * 设计原则：宁可漏点，也不误点。
 *  - 只在一个 App 刚切到前台的那 20 秒内动作
 *  - 绝不点击大面积容器（那等于点开广告）
 *  - 点完必须复查：按钮真没了才算成功
 *  - 用户一旦开始滚动，立刻退出，不再碰屏幕
 *
 * 识别分三级，从最可信到最可疑：
 *  1. 文字/描述里有「跳过 / skip / 关闭广告」——最可信
 *  2. View 的资源 id 或类名里有 skip / close / countdown——次可信
 *  3. 都认不出时，在右上角找「唯一的一个小方块」点一下——纯猜，只猜一次
 */
public class SkipService extends AccessibilityService {

    /** 开屏期：App 切前台后多久之内才允许动作。45 秒，覆盖冷启动慢、广告出来晚的。 */
    private static final long SPLASH_MS = 45000L;
    /** 点击后等多久复查。要短——广告按钮随时可能变得可点，查得慢就慢一拍。 */
    private static final long VERIFY_DELAY_MS = 250L;
    /** 同一次开屏最多点几次。广告按钮可能要等动画结束才响应，所以给够次数。 */
    private static final int MAX_ATTEMPTS = 8;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 3000;
    private static final int MAX_CANDIDATES = 8;
    /** 允许点击的最大面积（占屏幕比例）。超过它的节点一律不碰。 */
    private static final float MAX_TARGET_AREA = 0.20f;
    /** 标签归一化后的最大长度，超过就不是按钮是句子。 */
    private static final int MAX_LABEL_LEN = 10;
    /** 轮询间隔：有些广告整块是画出来的，系统一个事件都不发，只能自己主动看。 */
    private static final long POLL_MS = 400L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> foregroundAt = new HashMap<>();
    private final java.util.Set<String> warned = new java.util.HashSet<>();

    /** 当前认为「正在开屏」的包名。 */
    private String splashPkg;
    private long splashAt;
    /** 上一次处于前台的包名，用来判断「是不是刚切过来」。 */
    private String lastForegroundPkg;
    private int attempts;
    private String clickedLabel;
    /** 本次开屏已经结束（成功或放弃），不再动作。 */
    private boolean settled;
    /** 点击已发出、正在等复查。 */
    private boolean verifying;
    /** 点击发出时正在开屏的包名和时间，复查时用来确认「还是同一次开屏」。 */
    private String clickPkg;
    private long clickAt;
    /** 最近一次点击的目标位置，失败时写进记录，用来判断节点坐标可不可信。 */
    private Rect lastClickRect;
    /** 本次开屏已经点过的位置，避免反复点同一个候选。 */
    private final java.util.Set<String> triedRects = new java.util.HashSet<>();
    private int nodeBudget;
    private int screenW;
    private int screenH;
    private long lastBeatAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Prefs.beat(this);
        lastBeatAt = SystemClock.uptimeMillis();
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        final CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) {
            return;
        }
        final String pkg = pkgCs.toString();
        final long now = SystemClock.uptimeMillis();

        // 每 10 分钟留一次"我还活着"的痕迹，主页据此判断服务有没有被系统停掉
        if (now - lastBeatAt > 10L * 60L * 1000L) {
            lastBeatAt = now;
            Prefs.beat(this);
        }

        final int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // 用户开始操作屏幕了 —— 说明广告已经过去，或者他正在看内容。立刻收手。
            // 但如果点击已经发出、正在等复查，就绝不能在这里收手：广告关掉之后
            // 首页往往会自己滚一下，那样会把「这次跳过」的记录和计数一起吞掉。
            if (pkg.equals(splashPkg) && !verifying) {
                settle();
            }
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Long prev = foregroundAt.get(pkg);
            if (prev == null || now - prev > 2000L) {
                foregroundAt.put(pkg, now);
                if (foregroundAt.size() > 24) {
                    foregroundAt.clear();
                    foregroundAt.put(pkg, now);
                }
                // 只有「从一个 App 换到另一个 App」才算新开屏。
                // 同一个 App 内部弹个窗，不应该把 20 秒窗口重新计时，否则会越拖越晚。
                // 注意：桌面也算一次切换，否则「退出再进同一个 App」会被当成同一次开屏。
                if (!pkg.equals(lastForegroundPkg)) {
                    lastForegroundPkg = pkg;
                    if (isSystemPackage(pkg) || pkg.equals(getPackageName()) || isBlocked(pkg)) {
                        // 桌面 / 状态栏 / 自己的界面 / 排除名单 —— 本次开屏作废，别再轮询下去
                        if (splashPkg != null) {
                            settle();
                        }
                    } else {
                        startSplash(pkg, now);
                    }
                }
            }
        }

        if (!Prefs.isEnabled(this)) {
            return;
        }
        if (settled || verifying) {
            return;
        }
        if (splashPkg == null || !splashPkg.equals(pkg)) {
            return;
        }
        if (isSystemPackage(pkg) || pkg.equals(getPackageName())) {
            return;
        }
        tryAct();
    }

    /**
     * 真正干活的一步。事件和轮询都走这里。
     * 只按名字认（文字 / 描述 / 资源 id / 类名），认不出就不动。
     */
    private void tryAct() {
        if (settled || verifying || splashPkg == null) {
            return;
        }
        // 轮询不经过事件那条路，所以这里也要挡一次：自己的界面、系统界面、排除名单，绝不动
        if (splashPkg.equals(getPackageName()) || isSystemPackage(splashPkg) || isBlocked(splashPkg)) {
            return;
        }
        if (SystemClock.uptimeMillis() - splashAt > SPLASH_MS) {
            settle();
            return;
        }
        if (!screenActive()) {
            return;
        }

        // 只按名字认：文字、描述、资源 id、类名。
        // 「按位置猜」那条路已经误点过两次（番茄的「分类」、菜鸟点进微信），
        // 代价比漏点大得多，所以整条拿掉了。认不出就不动。
        AccessibilityNodeInfo node = nextCandidate();
        if (node == null) {
            return;
        }
        String label = matchOf(node);
        if (label != null && doClick(node, label)) {
            clickPkg = splashPkg;
            clickAt = SystemClock.uptimeMillis();
            verifying = true;
            handler.postDelayed(verifyTask, VERIFY_DELAY_MS);
        }
    }

    /**
     * 取一个「还没点过」的候选。
     * 同一条广告里可能有两个「跳过」节点（广告 SDK 常建隐藏副本），
     * 我们不知道哪个是真的，所以按顺序挨个试，而不是只挑一个。
     */
    private AccessibilityNodeInfo nextCandidate() {
        List<AccessibilityNodeInfo> all = findSkipNodes();
        AccessibilityNodeInfo first = null;
        for (AccessibilityNodeInfo n : all) {
            Rect r = boundsOf(n);
            if (r == null) {
                continue;
            }
            if (first == null) {
                first = n;
            }
            if (!triedRects.contains(rectKey(r))) {
                return n;
            }
        }
        // 全都点过了还没关掉：回到第一个接着点。
        // 有些广告的按钮要等动画结束才响应，早点的那几下是白点的。
        return first;
    }

    private static String rectKey(Rect r) {
        return r.left + "," + r.top + " " + r.width() + "x" + r.height();
    }

    /**
     * 主动轮询。有些广告整块是画出来的，系统不会发任何无障碍事件，
     * 只靠事件驱动就等于完全没看过屏幕。
     */
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (settled || splashPkg == null) {
                return;
            }
            if (SystemClock.uptimeMillis() - splashAt > SPLASH_MS) {
                // 点击已经发出、正在等复查时先别收，否则这次成功就白丢了
                if (!verifying) {
                    settle();
                }
                return;
            }
            if (!Prefs.isEnabled(SkipService.this)) {
                settle();
                return;
            }
            if (screenActive()) {
                tryAct();
            }
            if (!settled) {
                // 前 15 秒看密一点（广告一般这时出来），之后放慢，别一直空转
                long elapsed = SystemClock.uptimeMillis() - splashAt;
                handler.postDelayed(this, elapsed < 15000L ? POLL_MS : 900L);
            }
        }
    };

    /** 点击后的复查：目标真没了才算成功，还在就再试一次，超次数就记一条排查信息。 */
    private final Runnable verifyTask = new Runnable() {
        @Override
        public void run() {
            verifying = false;
            // 用点击当时记下的包名，而不是现在的 splashPkg：
            // 这中间可能已经切到别的 App 去了。
            final String pkg = clickPkg;
            if (pkg == null || splashPkg == null || !splashPkg.equals(pkg)) {
                return;
            }
            if (SystemClock.uptimeMillis() - clickAt > SPLASH_MS) {
                settle();
                return;
            }

            boolean gone = findSkipNodes().isEmpty();

            if (gone) {
                // 目标消失 = 广告真的被关掉了。这时才计数。
                Prefs.record(SkipService.this, appLabel(pkg), clickedLabel);
                settle();
                return;
            }

            // 没关掉：换下一个「还没点过」的候选（同一条广告可能有两个跳过节点）
            if (attempts < MAX_ATTEMPTS) {
                AccessibilityNodeInfo still = nextCandidate();
                if (still != null) {
                    String label = matchOf(still);
                    if (label != null && doClick(still, label)) {
                        clickPkg = pkg;
                        clickAt = SystemClock.uptimeMillis();
                        verifying = true;
                        handler.postDelayed(this, VERIFY_DELAY_MS);
                        return;
                    }
                }
            }

            settle();
            if (warned.add(pkg)) {
                // 失败时把「我们点的那个位置」记下来：这样才能判断节点坐标是否可信
                String where = "";
                if (lastClickRect != null) {
                    where = "（@" + lastClickRect.left + "," + lastClickRect.top
                            + " " + lastClickRect.width() + "x" + lastClickRect.height()
                            + " / 屏" + screenW + "x" + screenH + "）";
                }
                Prefs.note(SkipService.this, appLabel(pkg) + " · 点了 " + attempts + " 次，按钮还在" + where);
            }
        }
    };

    /** 结束本次开屏，把待执行的复查和轮询都取消掉。 */
    private void settle() {
        settled = true;
        verifying = false;
        handler.removeCallbacks(verifyTask);
        handler.removeCallbacks(pollTask);
    }

    private void startSplash(String pkg, long now) {
        handler.removeCallbacks(verifyTask);
        handler.removeCallbacks(pollTask);
        splashPkg = pkg;
        splashAt = now;
        attempts = 0;
        clickedLabel = null;
        settled = false;
        verifying = false;
        clickPkg = null;
        clickAt = 0L;
        lastClickRect = null;
        triedRects.clear();
        // 每次开屏都允许留一条 ⚠，否则同一个 App 只有第一次失败会出现在记录里
        warned.remove(pkg);
        handler.postDelayed(pollTask, POLL_MS);
    }

    /** 系统界面（状态栏、输入法、桌面）不参与判断，否则下拉通知栏会被当成一次开屏。 */
    private static boolean isSystemPackage(String pkg) {
        return pkg.startsWith("com.android.systemui")
                || pkg.equals("android")
                || pkg.startsWith("com.android.settings")
                || pkg.startsWith("com.android.permissioncontroller")
                || pkg.startsWith("com.google.android.permissioncontroller")
                || pkg.startsWith("com.android.launcher")
                || pkg.startsWith("com.miui.home")
                || pkg.startsWith("com.huawei.android.launcher")
                || pkg.contains("launcher");
    }

    /**
     * 排除名单：给「整屏都是跳转区、点哪里都跳第三方」的 App 留的开关。
     * 目前为空——菜鸟改成「优先按 id 认」之后又放回来了。
     * 以后如果哪个 App 再出现误跳，把包名加在这里最省事。
     */
    private static boolean isBlocked(String pkg) {
        return false;
    }

    /** 熄屏、锁屏时绝对不点。 */
    private boolean screenActive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                return false;
            }
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return km == null || !km.isKeyguardLocked();
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 一级 / 二级：按名字认
    // ------------------------------------------------------------------

    /** 扫所有窗口，返回所有候选（按遍历顺序，可能不止一个）。 */
    private List<AccessibilityNodeInfo> findSkipNodes() {
        if (screenW <= 0 || screenH <= 0) {
            screenW = getResources().getDisplayMetrics().widthPixels;
            screenH = getResources().getDisplayMetrics().heightPixels;
        }
        nodeBudget = MAX_NODES;
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
        } catch (Throwable ignored) {
            // 某些机型/系统版本上 getWindows 不可用，退回活动窗口
        }
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                if (found.size() >= MAX_CANDIDATES) {
                    break;
                }
                AccessibilityNodeInfo root = null;
                try {
                    root = w.getRoot();
                } catch (Throwable ignored) {
                }
                if (root != null) {
                    collect(root, 0, found);
                }
            }
        }
        if (found.isEmpty()) {
            AccessibilityNodeInfo active = null;
            try {
                active = getRootInActiveWindow();
            } catch (Throwable ignored) {
            }
            if (active != null) {
                collect(active, 0, found);
            }
        }
        // 排序：靠资源 id / 类名认出来的排前面，纯文字匹配的排后面。
        // 文字可能只是个视觉标签（点下去落到广告的跳转区），
        // 而带 skip 的 id 通常是真正的按钮——菜鸟那条广告就是这么错过一次的。
        Collections.sort(found, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                int ra = rankOf(a);
                int rb = rankOf(b);
                if (ra != rb) {
                    return ra - rb;
                }
                Rect ar = boundsOf(a);
                Rect br = boundsOf(b);
                long aa = ar == null ? Long.MAX_VALUE : (long) ar.width() * ar.height();
                long ab = br == null ? Long.MAX_VALUE : (long) br.width() * br.height();
                return Long.compare(aa, ab);
            }
        });
        return found;
    }

    /** 匹配可信度：id / 类名（0）> 裸「跳过」（1）> 其它文字（2）。 */
    private int rankOf(AccessibilityNodeInfo node) {
        String label = matchOf(node);
        if (label == null) {
            return 3;
        }
        if (label.startsWith("id:") || label.startsWith("class:")) {
            return 0;
        }
        String n = normalize(label);
        if (n.equals("跳过") || n.equals("跳過") || n.equals("skip")
                || n.equals("跳过广告") || n.equals("跳過廣告")) {
            return 1;
        }
        return 2;
    }

    private void collect(AccessibilityNodeInfo node, int depth, List<AccessibilityNodeInfo> out) {
        if (node == null || depth > MAX_DEPTH || out.size() >= MAX_CANDIDATES) {
            return;
        }
        if (--nodeBudget <= 0) {
            return;
        }
        if (isCandidate(node)) {
            out.add(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count && out.size() < MAX_CANDIDATES; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
            }
            if (child != null) {
                collect(child, depth + 1, out);
            }
        }
    }

    /** 候选的五个硬条件：属于当前 App、能认得出来、看得见、够小、不在滚动列表里。 */
    private boolean isCandidate(AccessibilityNodeInfo node) {
        String label = matchOf(node);
        if (label == null) {
            return false;
        }
        try {
            // 只认正在开屏的那个 App 自己的节点，别去点别的应用的浮层
            CharSequence np = node.getPackageName();
            if (np == null || !np.toString().equals(splashPkg)) {
                return false;
            }
            if (!node.isVisibleToUser() || !node.isEnabled()) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        Rect r = boundsOf(node);
        if (r == null || r.width() < 8 || r.height() < 8) {
            return false;
        }
        if (areaRatio(r) > MAX_TARGET_AREA) {
            return false;
        }
        // 可滚动容器里的东西一律不碰，只有「最裸的跳过」例外——
        // 那条规则是为了挡「跳过本章」，而裸的「跳过」出现在 WebView 广告里很常见。
        if (insideScrollable(node) && !isBareSkip(label)) {
            return false;
        }
        // 「关闭X」只在屏幕上三成以内认：开屏广告的关闭键都在顶部角落，
        // 屏幕中间的「关闭」多半是普通弹窗——设置页、聊天窗里都有一堆，不该碰。
        if (!label.startsWith("id:") && !label.startsWith("class:")) {
            String n = normalize(label);
            if ((n.startsWith("关闭") || n.startsWith("關閉")) && r.top > screenH * 0.30f) {
                return false;
            }
        }
        return true;
    }

    /** 正文里的「跳过这章」几乎一定在可滚动列表里；开屏的跳过按钮不在。 */
    private boolean insideScrollable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int up = 0; up < 6 && cur != null; up++) {
            try {
                if (cur.isScrollable()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            cur = parentOf(cur);
        }
        return false;
    }

    /**
     * 认出一个「像跳过按钮」的节点，返回它是凭什么被认出来的（会写进日志）。
     * 顺序：文字 > 描述 > 资源 id > 类名。
     */
    private String matchOf(AccessibilityNodeInfo node) {
        CharSequence text = null;
        CharSequence desc = null;
        try {
            text = node.getText();
            desc = node.getContentDescription();
        } catch (Throwable ignored) {
        }
        if (isSkipLabel(text)) {
            return text.toString().trim();
        }
        if (isSkipLabel(desc)) {
            return desc.toString().trim();
        }
        String id = idOf(node);
        if (id != null && looksLikeSkipName(idTail(id))) {
            return "id:" + tail(id, 30);
        }
        String cls = classOf(node);
        if (cls != null && looksLikeSkipName(classTail(cls))) {
            return "class:" + tail(cls, 30);
        }
        return null;
    }

    /**
     * 标签匹配。归一化后必须是「跳过 / 跳過 / skip / 关闭广告」这一族的开头，
     * 且不能是句子（长度 ≤ 10）。
     */
    private static boolean isSkipLabel(CharSequence cs) {
        if (cs == null) {
            return false;
        }
        String s = normalize(cs);
        if (s.isEmpty() || s.length() > MAX_LABEL_LEN) {
            return false;
        }
        if (s.startsWith("跳过") || s.startsWith("跳過")) {
            return true;
        }
        if (s.startsWith("skip")) {
            return true;
        }
        // 关闭 / 關閉 + 短后缀：关闭、关闭奇秀、关闭广告、关闭此广告。
        // 不做「单独的关闭」以外的放宽——长度上限 6 个字。
        if ((s.startsWith("关闭") || s.startsWith("關閉")) && s.length() <= 6) {
            return true;
        }
        return false;
    }

    /**
     * 只有「最裸」的标签才允许出现在可滚动容器里（WebView 广告常见）。
     * 「跳过本章」「跳过片头」这种带上下文的，仍然要求不在滚动容器里。
     */
    private static boolean isBareSkip(String label) {
        if (label == null) {
            return false;
        }
        String s = label.toLowerCase(Locale.US);
        if (s.startsWith("id:") || s.startsWith("class:")) {
            return true;
        }
        String n = normalize(label);
        return n.equals("跳过") || n.equals("跳過") || n.equals("skip")
                || n.equals("跳过广告") || n.equals("跳過廣告");
    }

    /** 去掉空白、数字、分隔符，把「跳过 5s」和「跳过 4s」归到同一个前缀上。 */
    private static String normalize(CharSequence cs) {
        String s = cs.toString().toLowerCase(Locale.US).trim();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c >= '0' && c <= '9') {
                continue;
            }
            if (c == '|' || c == '·' || c == '/' || c == '：' || c == ':') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 资源 id / 类名里有没有「跳过」的线索。
     * 广告 SDK 起名一般很直白：skip / close / countdown / dismiss。
     *
     * 注意：只看名字的尾部，不能看全路径——
     * 否则包名里带 skip 的 App（比如秒过自己 com.miaoguo.skip）会把每个控件都算成跳过按钮。
     */
    private static boolean looksLikeSkipName(String s) {
        String v = s.toLowerCase(Locale.US);
        return v.contains("skip")
                || v.contains("countdown")
                || v.contains("count_down")
                || v.contains("dismiss");
    }

    /** 资源 id 只取 ":id/" 后面那段，例如 com.foo.skip:id/btn_x → btn_x。 */
    private static String idTail(String id) {
        int i = id.indexOf(":id/");
        if (i >= 0) {
            return id.substring(i + 4);
        }
        int j = id.lastIndexOf('/');
        if (j >= 0) {
            return id.substring(j + 1);
        }
        return id;
    }

    /** 类名只取最后一段，例如 com.miaoguo.skip.ToggleSwitch → ToggleSwitch。 */
    private static String classTail(String cls) {
        int i = cls.lastIndexOf('.');
        if (i >= 0 && i < cls.length() - 1) {
            return cls.substring(i + 1);
        }
        return cls;
    }

    private static String idOf(AccessibilityNodeInfo node) {
        try {
            String v = node.getViewIdResourceName();
            return (v == null || v.length() == 0) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String classOf(AccessibilityNodeInfo node) {
        try {
            CharSequence cs = node.getClassName();
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    /**
     * 点一下。顺序是「先稳后准」：
     *  第一次：节点自己可点 → 无障碍点击；否则点最近的小面积可点父控件
     *  兜底：  按目标自己的坐标模拟一次触摸
     *  重试：  直接上真实触摸，再退父控件
     *
     * 无障碍点击不依赖坐标，对标准控件更安全；广告 SDK 多半不认它，
     * 所以靠缩短复查间隔（250ms）尽快补上真实触摸，而不是一上来就点坐标。
     *
     * 全程拒绝面积超过屏幕 20% 的容器，绝不盲点屏幕。
     */
    private boolean doClick(AccessibilityNodeInfo node, String label) {
        if (attempts >= MAX_ATTEMPTS) {
            return false;
        }
        Rect r = boundsOf(node);
        if (r == null || areaRatio(r) > MAX_TARGET_AREA) {
            return false;
        }
        boolean fired = false;
        // 第一次走无障碍点击：不依赖坐标，对标准控件最稳
        if (attempts == 0) {
            try {
                if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    fired = true;
                }
            } catch (Throwable ignored) {
            }
            if (!fired) {
                fired = clickSmallAncestor(node);
            }
        }
        if (!fired) {
            // 真实触摸点「跳过」自己的中心
            fired = tap(r.centerX(), r.centerY());
        }
        if (!fired && attempts > 0) {
            fired = clickSmallAncestor(node);
        }
        if (fired) {
            attempts++;
            clickedLabel = label;
            lastClickRect = new Rect(r);
            triedRects.add(rectKey(r));
        }
        return fired;
    }

    /** 往上最多 3 层，找第一个「面积不超过屏幕 20% 且可点」的父控件。 */
    private AccessibilityNodeInfo smallClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = parentOf(node);
        for (int up = 0; up < 3 && cur != null; up++) {
            Rect pr = boundsOf(cur);
            if (pr != null && areaRatio(pr) <= MAX_TARGET_AREA) {
                try {
                    if (cur.isClickable()) {
                        return cur;
                    }
                } catch (Throwable ignored) {
                }
            }
            cur = parentOf(cur);
        }
        return null;
    }

    /** 直接对那个可点父控件发一次无障碍点击。 */
    private boolean clickSmallAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = smallClickableAncestor(node);
        if (target == null) {
            return false;
        }
        try {
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable t) {
            return false;
        }
    }

    private Rect boundsOf(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Throwable t) {
            return null;
        }
        if (r.width() <= 0 || r.height() <= 0) {
            return null;
        }
        return r;
    }

    private float areaRatio(Rect r) {
        long screen = (long) screenW * (long) screenH;
        if (screen <= 0) {
            return 1f;
        }
        return ((float) r.width() * (float) r.height()) / (float) screen;
    }

    private static AccessibilityNodeInfo parentOf(AccessibilityNodeInfo node) {
        try {
            return node.getParent();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean tap(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0L, 100L);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 日志里显示应用名，而不是 com.xxx.yyy 这种包名。 */
    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) {
                return cs.toString();
            }
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    @Override
    public void onInterrupt() {
        // 不需要处理
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(verifyTask);
        handler.removeCallbacks(pollTask);
        super.onDestroy();
    }
}
