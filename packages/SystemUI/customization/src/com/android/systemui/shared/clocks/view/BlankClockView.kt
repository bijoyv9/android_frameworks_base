/*
 * Copyright (C) 2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shared.clocks.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.runtime.Composable

/**
 * A clock view that renders nothing and acts as a spacer.
 * Used to effectively "hide" the clock on the lockscreen while preserving layout structure.
 */
class BlankClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun getTag(): String = "BlankClockView"

    @Composable
    override fun Content() {
        // Intentionally empty
    }

    override val clockHeightBase: Int
        get() {
            val metrics = context.resources.displayMetrics
            val height = metrics.heightPixels
            // Use ~55% of screen height as a spacer to prevent notification overlap
            // with wallpaper-integrated clocks.
            return (height * 0.55f).toInt().coerceAtLeast(1)
        }
}
