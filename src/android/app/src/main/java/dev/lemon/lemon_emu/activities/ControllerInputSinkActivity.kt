// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.activities

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.utils.InputHandler
import dev.lemon.lemon_emu.utils.Log
import java.lang.ref.WeakReference

/**
 * Keeps controller buttons working while the game runs on an external display in extended mode.
 *
 * Android keeps a separate input focus per display, and on some devices key events from a
 * gamepad are delivered to the phone's own display, where the launcher gets them (pressing a
 * button opens its search and the keyboard), while stick motion still reaches the game. This
 * activity is shown on the phone's display while the emulator is on an external display, takes
 * the focus there and hands every controller event to [EmulationActivity].
 */
class ControllerInputSinkActivity : Activity() {

    private var createdAt = 0L
    private var refocusTried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        createdAt = SystemClock.uptimeMillis()
        setContentView(
            TextView(this).apply {
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                textSize = 16f
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                setText(R.string.controller_input_sink_message)
            }
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Some vendors open their own panel on the phone's display when an external display is
        // connected (RedMagic shows a touchpad), right after this activity, and it takes the
        // focus. Come back to the front once, only in the first seconds, so a window the user
        // opens later on the phone is left alone.
        if (!hasFocus && !refocusTried && !isFinishing &&
            SystemClock.uptimeMillis() - createdAt < REFOCUS_WINDOW_MS
        ) {
            refocusTried = true
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && !hasWindowFocus()) {
                    bringToFront(this)
                }
            }, REFOCUS_DELAY_MS)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // The user wants the phone screen back: do not reopen until the game returns to it.
        dismissedByUser = true
        finish()
    }

    override fun onDestroy() {
        if (current?.get() === this) {
            current = null
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val emulation = emulationActivity()
        if (emulation != null && InputHandler.isPhysicalGameController(event.device)) {
            return emulation.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val emulation = emulationActivity()
        if (emulation != null && InputHandler.isPhysicalGameController(event.device)) {
            return emulation.dispatchGenericMotionEvent(event)
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun emulationActivity(): EmulationActivity? =
        EmulationActivity.current?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    companion object {
        private const val REFOCUS_WINDOW_MS = 5000L
        private const val REFOCUS_DELAY_MS = 300L

        @Volatile
        private var current: WeakReference<ControllerInputSinkActivity>? = null

        @Volatile
        var dismissedByUser = false

        fun isShowing(): Boolean = current?.get()?.let { !it.isFinishing } == true

        fun show(context: Context) {
            if (isShowing() || dismissedByUser) {
                return
            }
            val intent = Intent(context, ControllerInputSinkActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            runCatching {
                context.startActivity(intent, options.toBundle())
            }.onFailure {
                Log.warning("[ControllerInputSink] Could not show on the default display: ${it.message}")
            }
        }

        private fun bringToFront(context: Context) {
            val intent = Intent(context, ControllerInputSinkActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            runCatching { context.startActivity(intent, options.toBundle()) }
        }

        fun hide() {
            current?.get()?.finish()
            current = null
        }
    }
}
