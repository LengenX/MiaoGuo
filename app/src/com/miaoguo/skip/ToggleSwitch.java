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

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 自绘开关。
 * 系统自带的 Switch 在这个主题下开了关了几乎一个颜色，尺寸也偏小，
 * 所以这里自己画一个：绿色 = 开，深蓝灰 = 关，尺寸在布局里给到 62x36dp。
 */
public class ToggleSwitch extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(ToggleSwitch view, boolean checked);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final RectF trackRect = new RectF();

    private boolean checked = false;
    private float progress = 0f;
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;

    private final int colorOff;
    private final int colorOn;
    private final int colorThumb;
    private final float insetRatio = 0.11f;

    public ToggleSwitch(Context c) {
        this(c, null);
    }

    public ToggleSwitch(Context c, AttributeSet attrs) {
        super(c, attrs);
        colorOff = getResources().getColor(R.color.switch_off);
        colorOn = getResources().getColor(R.color.accent);
        colorThumb = getResources().getColor(R.color.switch_thumb);
        trackPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setStyle(Paint.Style.FILL);
        setClickable(true);
        setFocusable(true);
        updateDesc();
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value) {
        setChecked(value, false);
    }

    public void setChecked(boolean value, boolean animate) {
        checked = value;
        float target = value ? 1f : 0f;
        if (animate) {
            animateTo(target);
        } else {
            progress = target;
            invalidate();
        }
        updateDesc();
    }

    private void animateTo(float target) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(180L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    private void toggle() {
        setChecked(!checked, true);
        if (listener != null) {
            listener.onCheckedChanged(this, checked);
        }
    }

    private void updateDesc() {
        setContentDescription(checked ? "总开关，已开启" : "总开关，已关闭");
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int defW = (int) (62 * getResources().getDisplayMetrics().density);
        int defH = (int) (36 * getResources().getDisplayMetrics().density);
        int w = resolveSize(defW, widthSpec);
        int h = resolveSize(defH, heightSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        final float r = h / 2f;

        int track = (Integer) argb.evaluate(progress, colorOff, colorOn);
        trackPaint.setColor(track);
        trackRect.set(0f, 0f, w, h);
        canvas.drawRoundRect(trackRect, r, r, trackPaint);

        final float inset = h * insetRatio;
        final float thumbR = r - inset;
        final float cx = r + (w - 2f * r) * progress;
        thumbPaint.setColor(colorThumb);
        canvas.drawCircle(cx, h / 2f, thumbR, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        toggle();
        return true;
    }
}
