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
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * 免责声明页。首次打开时先看这一页，之后可以从主页底部重新打开。
 * 内容和 README 里那几段是对齐的：能做什么、支付检测、不联网、免责。
 */
public class DisclaimerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_disclaimer);

        Prefs.setDisclaimerSeen(this);

        TextView ok = findViewById(R.id.btn_disclaimer_ok);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 第一次来时，看完声明接着进引导页；已经看过引导就直接回主页
                if (!Prefs.isGuideSeen(DisclaimerActivity.this)) {
                    startActivity(new Intent(DisclaimerActivity.this, GuideActivity.class));
                }
                finish();
            }
        });
    }
}
