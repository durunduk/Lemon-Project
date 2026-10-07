// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.activities

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import dev.lemon.lemon_emu.utils.InputHandler
import java.lang.ref.WeakReference

/**
 * Keeps controller buttons working while the game runs on an external display in extended mode.
 *
 * Android keeps a separate input focus per display, and on some devices key events from a
 * gamepad are delivered to the phone's own display, where the launcher gets them (pressing a
 * button opens its search and the keyboard), while stick motion still reaches the game. This
 * activity is shown on the phone's display while the emulator is on another display, takes the
 * focus there and hands every controller event to the emulator.
 */
class ControllerInputSinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        setContentView(
            TextView(this).apply {
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                textSize = 16f
                setPadding(48, 48, 48, 48)
                text = "Lemon: игра на внешнем экране.\n" +
                    "Ввод с геймпада передаётся в эмулятор.\n\n" +
                    "Нажмите «Назад» на телефоне, чтобы закрыть этот экран."
            }
        )
        android.util.Log.i(InputHandler.DEBUG_TAG, "inputSink created displayId=${display?.displayId}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.i(
            InputHandler.DEBUG_TAG,
            "inputSink windowFocus=$hasFocus displayId=${display?.displayId}"
        )
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
        val emulation = EmulationActivity.current?.get()
        if (emulation != null && !emulation.isFinishing &&
            InputHandler.isPhysicalGameController(event.device)
        ) {
            android.util.Log.d(InputHandler.DEBUG_TAG, "inputSink forward key=${event.keyCode}")
            return emulation.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val emulation = EmulationActivity.current?.get()
        if (emulation != null && !emulation.isFinishing &&
            InputHandler.isPhysicalGameController(event.device)
        ) {
            return emulation.dispatchGenericMotionEvent(event)
        }
        return super.dispatchGenericMotionEvent(event)
    }

    companion object {
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
                android.util.Log.w(InputHandler.DEBUG_TAG, "inputSink launch failed: ${it.message}")
            }
        }

        fun hide() {
            current?.get()?.finish()
            current = null
        }
    }
}
