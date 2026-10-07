// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.lemon.lemon_emu.features.input.NativeInput
import dev.lemon.lemon_emu.features.input.LemonInputOverlayDevice
import dev.lemon.lemon_emu.features.input.LemonPhysicalDevice

object InputHandler {
    var androidControllers = mapOf<Int, LemonPhysicalDevice>()
    var registeredControllers = mutableListOf<ParamPackage>()

    private val controllerButtons = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT
    )

    private val controllerAxes = intArrayOf(
        MotionEvent.AXIS_X,
        MotionEvent.AXIS_Y,
        MotionEvent.AXIS_Z,
        MotionEvent.AXIS_RX,
        MotionEvent.AXIS_RY,
        MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_HAT_X,
        MotionEvent.AXIS_HAT_Y,
        MotionEvent.AXIS_LTRIGGER,
        MotionEvent.AXIS_RTRIGGER
    )

    // Currently, Android doesn't support Joy-Con D-pad buttons. We fall back to the scan code
    private const val LINUX_BUTTON_DPAD_UP = 0x220
    private const val LINUX_BUTTON_DPAD_DOWN = 0x221
    private const val LINUX_BUTTON_DPAD_LEFT = 0x222
    private const val LINUX_BUTTON_DPAD_RIGHT = 0x223

    fun isPhysicalGameController(device: InputDevice?): Boolean {
        device ?: return false

        if (device.isVirtual) {
            return false
        }

        val sources = device.sources
        val hasControllerSource =
            sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!hasControllerSource) {
            return false
        }

        val hasControllerButtons = device.hasKeys(*controllerButtons).any { it }
        val hasControllerAxes = device.motionRanges.any { range ->
            controllerAxes.contains(range.axis)
        }
        return hasControllerButtons || hasControllerAxes
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> NativeInput.ButtonState.PRESSED
            KeyEvent.ACTION_UP -> NativeInput.ButtonState.RELEASED
            else -> return false
        }

        var controllerData = androidControllers[event.device.controllerNumber]
        if (controllerData == null) {
            updateControllerData()
            controllerData = androidControllers[event.device.controllerNumber]
            if (controllerData == null) {
                android.util.Log.w(
                    DEBUG_TAG,
                    "DROPPED key=${event.keyCode} deviceId=${event.deviceId} " +
                        "controllerNumber=${event.device?.controllerNumber} known=${androidControllers.keys}"
                )
                return false
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            android.util.Log.d(
                DEBUG_TAG,
                "key=${event.keyCode} deviceId=${event.deviceId} " +
                    "-> guid=${controllerData.getGUID()} port=${controllerData.getPort()}"
            )
        }

        NativeInput.onGamePadButtonEvent(
            controllerData.getGUID(),
            controllerData.getPort(),
            getButtonIdFromEvent(event),
            action
        )
        return true
    }

    fun getButtonIdFromEvent(event: KeyEvent): Int {
        if (event.keyCode == 0) {
            return when (event.scanCode) {
                LINUX_BUTTON_DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
                LINUX_BUTTON_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                LINUX_BUTTON_DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                LINUX_BUTTON_DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                else -> return 0
            }
        }
        return event.keyCode
    }

    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val controllerData = androidControllers[event.device.controllerNumber]
        if (controllerData == null) {
            android.util.Log.w(
                DEBUG_TAG,
                "DROPPED motion deviceId=${event.deviceId} " +
                    "controllerNumber=${event.device?.controllerNumber} known=${androidControllers.keys}"
            )
            return false
        }
        event.device.motionRanges.forEach {
            NativeInput.onGamePadAxisEvent(
                controllerData.getGUID(),
                controllerData.getPort(),
                it.axis,
                event.getAxisValue(it.axis)
            )
        }
        return true
    }

    // Ports a controller got earlier in this process, keyed by its stable descriptor. The saved
    // button mapping is bound to (GUID, port), so a controller must keep its port when the device
    // list changes, e.g. when an external display is attached in extended mode and Android
    // re-enumerates input devices or the activity is recreated.
    private val stablePorts = mutableMapOf<String, Int>()
    private const val MAX_PORTS = 8
    const val DEBUG_TAG = "LemonInputDbg"

    fun getDevices(): Map<Int, LemonPhysicalDevice> {
        val gameControllerDeviceIds = mutableMapOf<Int, LemonPhysicalDevice>()
        val inputSettings = NativeConfig.getInputSettings(true)

        // One entry per physical controller: several InputDevices can share a controllerNumber.
        val controllers = linkedMapOf<Int, InputDevice>()
        InputDevice.getDeviceIds().forEach { deviceId ->
            val device = InputDevice.getDevice(deviceId) ?: return@forEach
            val isController = isPhysicalGameController(device)
            android.util.Log.i(
                DEBUG_TAG,
                "device id=$deviceId name='${device.name}' controllerNumber=${device.controllerNumber} " +
                    "sources=0x${Integer.toHexString(device.sources)} vid=0x${Integer.toHexString(device.vendorId)} " +
                    "pid=0x${Integer.toHexString(device.productId)} virtual=${device.isVirtual} " +
                    "descriptor=${device.descriptor} isController=$isController"
            )
            if (isController && !controllers.containsKey(device.controllerNumber)) {
                controllers[device.controllerNumber] = device
            }
        }

        // Forget controllers that are gone, so their ports can be reused.
        val presentDescriptors = controllers.values.map { it.descriptor }.toSet()
        stablePorts.keys.retainAll(presentDescriptors)

        // Controllers with a saved mapping claim their ports before others take the free ones.
        val ordered = controllers.entries.sortedBy {
            if (stablePorts.containsKey(it.value.descriptor) ||
                savedPortForGuid(it.value.getGUID(), inputSettings) != null
            ) 0 else 1
        }
        ordered.forEach { (controllerNumber, device) ->
            val port = stablePorts.getOrPut(device.descriptor) {
                // Prefer the port the saved mapping for this controller's GUID points at.
                val savedPort = savedPortForGuid(device.getGUID(), inputSettings)
                if (savedPort != null && savedPort !in stablePorts.values) {
                    savedPort
                } else {
                    (0 until MAX_PORTS).firstOrNull { it !in stablePorts.values } ?: (MAX_PORTS - 1)
                }
            }
            gameControllerDeviceIds[controllerNumber] = LemonPhysicalDevice(
                device,
                port,
                inputSettings[port].useSystemVibrator
            )
            android.util.Log.i(
                DEBUG_TAG,
                "controller '${device.name}' controllerNumber=$controllerNumber -> port=$port guid=${device.getGUID()}"
            )
        }
        return gameControllerDeviceIds
    }

    private fun savedPortForGuid(
        guid: String,
        inputSettings: Array<dev.lemon.lemon_emu.features.input.model.PlayerInput>
    ): Int? {
        inputSettings.forEach { player ->
            player.buttons.forEach { serialized ->
                if (serialized.isEmpty()) return@forEach
                val params = ParamPackage(serialized)
                if (params.get("engine", "") == "android" &&
                    params.get("guid", "").equals(guid, ignoreCase = true) &&
                    params.has("port")
                ) {
                    return params.get("port", 0).takeIf { it in 0 until MAX_PORTS }
                }
            }
        }
        return null
    }

    fun updateControllerData() {
        android.util.Log.i(DEBUG_TAG, "updateControllerData()")
        androidControllers = getDevices()
        androidControllers.forEach {
            NativeInput.registerController(it.value)
        }

        // Register the input overlay on a dedicated port for all player 1 vibrations
        NativeInput.registerController(LemonInputOverlayDevice(androidControllers.isEmpty(), 100))
        registeredControllers.clear()
        NativeInput.getInputDevices().forEach {
            registeredControllers.add(ParamPackage(it))
        }
        registeredControllers.sortBy { it.get("port", 0) }
    }

    fun InputDevice.getGUID(): String = String.format("%016x%016x", productId, vendorId)
}
