package com.aaassseee.screen_brightness_android

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.annotation.NonNull
import com.aaassseee.screen_brightness_android.stream_handler.CurrentBrightnessChangeStreamHandler
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import kotlin.math.*
import kotlin.properties.Delegates

/**
 * ScreenBrightnessAndroidPlugin setting screen brightness
 */
class ScreenBrightnessAndroidPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    /**
     * The MethodChannel that will the communication between Flutter and native Android
     *
     * This local reference serves to register the plugin with the Flutter Engine and unregister it
     * when the Flutter Engine is detached from the Activity
     */
    private lateinit var methodChannel: MethodChannel

    private lateinit var currentBrightnessChangeEventChannel: EventChannel

    private var currentBrightnessChangeStreamHandler: CurrentBrightnessChangeStreamHandler? = null

    private var activity: Activity? = null

    /**
     * The value which will be init when this plugin is attached to the Flutter engine
     *
     * This value refer to the brightness value between 0 and 1 when the application initialized.
     */
    private var systemBrightness by Delegates.notNull<Float>()

    /**
     * The value which will be init when this plugin is attached to the Flutter engine
     *
     * This value refer to the minimum brightness value.
     *
     * By system default the value should be 0.0f, however it varies in some OS, e.g. POCO series.
     * Should not be changed in the future
     */
    private var minimumBrightness by Delegates.notNull<Int>()

    /**
     * The value which will be init when this plugin is attached to the Flutter engine
     *
     * This value refer to the maximum brightness value.
     *
     * By system default the value should be 255.0f, however it varies in some OS, e.g. POCO series.
     * Should not be changed in the future
     */
    private var maximumBrightness by Delegates.notNull<Int>()

    /**
     * The value which will be set when user called [handleSetScreenBrightnessMethodCall]
     * or [handleResetScreenBrightnessMethodCall]
     *
     * This value refer to the brightness value between 0 to 1 when user called [handleSetScreenBrightnessMethodCall].
     */
    private var changedBrightness: Float? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel = MethodChannel(
            flutterPluginBinding.binaryMessenger,
            "github.com/aaassseee/screen_brightness"
        )
        methodChannel.setMethodCallHandler(this)


        currentBrightnessChangeEventChannel = EventChannel(
            flutterPluginBinding.binaryMessenger,
            "github.com/aaassseee/screen_brightness/change"
        )

        try {
            minimumBrightness = getScreenMinimumBrightness(flutterPluginBinding.applicationContext)
            maximumBrightness = getScreenMaximumBrightness(flutterPluginBinding.applicationContext)
            systemBrightness = getSystemBrightness(flutterPluginBinding.applicationContext)
        } catch (e: Settings.SettingNotFoundException) {
            e.printStackTrace()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity

        currentBrightnessChangeStreamHandler =
            CurrentBrightnessChangeStreamHandler(
                binding.activity,
                onListenStart = null,
                onChange = { eventSink ->
                    systemBrightness = getSystemBrightness(binding.activity)
                    if (changedBrightness == null) {
                        eventSink.success(systemBrightness)
                    }
                })
        currentBrightnessChangeEventChannel.setStreamHandler(currentBrightnessChangeStreamHandler)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        when (call.method) {
            "getSystemScreenBrightness" -> handleGetSystemBrightnessMethodCall(result)
            "getScreenBrightness" -> handleGetScreenBrightnessMethodCall(result)
            "setScreenBrightness" -> handleSetScreenBrightnessMethodCall(call, result)
            "resetScreenBrightness" -> handleResetScreenBrightnessMethodCall(result)
            "hasChanged" -> handleHasChangedMethodCall(result)
            else -> result.notImplemented()
        }
    }

    private fun getScreenMinimumBrightness(context: Context): Int {
        try {
            val powerManager: PowerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager?
                    ?: throw ClassNotFoundException()

            powerManager.javaClass.declaredMethods.forEach {
                if (it.name.equals("getMinimumScreenBrightnessSetting")) {
                    it.isAccessible = true
                    return it.invoke(powerManager) as Int
                }
            }

            powerManager.javaClass.declaredFields.forEach {
                if (it.name.equals("BRIGHTNESS_OFF")) {
                    it.isAccessible = true
                    return it[powerManager] as Int
                }
            }

            return 0
        } catch (e: Exception) {
            return 0
        }
    }

    private fun getScreenMaximumBrightness(context: Context): Int {
        try {
            val powerManager: PowerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager?
                    ?: throw ClassNotFoundException()
            powerManager.javaClass.declaredMethods.forEach {
                if (it.name.equals("getMaximumScreenBrightnessSetting")) {
                    it.isAccessible = true
                    return it.invoke(powerManager) as Int
                }
            }

            powerManager.javaClass.declaredFields.forEach {
                if (it.name.equals("BRIGHTNESS_ON")) {
                    it.isAccessible = true
                    return it[powerManager] as Int
                }
            }

            return 255
        } catch (e: Exception) {
            return 255
        }
    }

    private fun getSystemBrightness(context: Context): Float {
        val brightness = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS
        )

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                Settings.System.getFloat(
                    context.contentResolver,
                    "screen_brightness_float"
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> convertLinearToGamma(
                brightness.toFloat(),
                minimumBrightness.toFloat(),
                maximumBrightness.toFloat()
            )
            else -> norm(minimumBrightness.toFloat(), maximumBrightness.toFloat(), brightness.toFloat())
        }
    }

    private fun norm(start: Float, stop: Float, value: Float): Float {
        return (value - start) / (stop - start)
    }

    fun lerp(start: Float, stop: Float, amount: Float): Float {
        return start + (stop - start) * amount
    }

    private fun convertLinearToGamma(
        brightness: Float,
        minimumBrightness: Float,
        maximumBrightness: Float
    ): Float {
        val gammaSpaceMax = 1023f
        val r = 0.5f
        val a = 0.17883277f
        val b = 0.28466892f
        val c = 0.55991073f

        val normalizedVal: Float = norm(minimumBrightness, maximumBrightness, brightness) * 12
        val ret: Float = if (normalizedVal <= 1f) {
            sqrt(normalizedVal) * r
        } else {
            a * ln(normalizedVal - b) + c
        }

        // HLG is normalized to the range [0, 12], so we need to re-normalize to the range [0, 1]
        // in order to derive the correct setting value.
        // HLG is normalized to the range [0, 12], so we need to re-normalize to the range [0, 1]
        // in order to derive the correct setting value.
        return round(lerp(0f, gammaSpaceMax, ret)) / gammaSpaceMax
    }

    private fun handleGetSystemBrightnessMethodCall(result: MethodChannel.Result) {
        result.success(systemBrightness)
    }

    private fun handleGetScreenBrightnessMethodCall(result: MethodChannel.Result) {
        val activity = activity
        if (activity == null) {
            result.error("-10", "Unexpected error on activity binding", null)
            return
        }

        var brightness: Float?
        // get current window attribute brightness
        val layoutParams: WindowManager.LayoutParams = activity.window.attributes
        brightness = layoutParams.screenBrightness
        // check brightness changed
        if (brightness.sign != -1.0f) {
            // return changed brightness
            result.success(brightness)
            return
        }

        // get system setting brightness
        try {
            brightness = getSystemBrightness(activity)
            result.success(brightness)
        } catch (e: Settings.SettingNotFoundException) {
            e.printStackTrace()
            result.error("-11", "Could not found system setting screen brightness value", null)
            return
        }
    }


    private fun setWindowsAttributesBrightness(brightness: Float): Boolean {
        return try {
            val layoutParams: WindowManager.LayoutParams = activity!!.window.attributes
            layoutParams.screenBrightness = brightness
            activity!!.window.attributes = layoutParams
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun handleSetScreenBrightnessMethodCall(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val activity = activity
        if (activity == null) {
            result.error("-10", "Unexpected error on activity binding", null)
            return
        }

        val brightness: Float? = (call.argument("brightness") as? Double)?.toFloat()
        if (brightness == null) {
            result.error("-2", "Unexpected error on null brightness", null)
            return
        }

        val isSet = setWindowsAttributesBrightness(brightness)
        if (!isSet) {
            result.error("-1", "Unable to change screen brightness", null)
            return
        }

        changedBrightness = brightness
        handleCurrentBrightnessChanged(brightness)
        result.success(null)
    }

    private fun handleResetScreenBrightnessMethodCall(result: MethodChannel.Result) {
        val activity = activity
        if (activity == null) {
            result.error("-10", "Unexpected error on activity binding", null)
            return
        }

        val isSet =
            setWindowsAttributesBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        if (!isSet) {
            result.error("-1", "Unable to change screen brightness", null)
            return
        }

        changedBrightness = null
        handleCurrentBrightnessChanged(systemBrightness)
        result.success(null)
    }

    private fun handleCurrentBrightnessChanged(currentBrightness: Float) {
        currentBrightnessChangeStreamHandler?.addCurrentBrightnessToEventSink(currentBrightness.toDouble())
    }

    private fun handleHasChangedMethodCall(result: MethodChannel.Result) {
        result.success(changedBrightness != null)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
        currentBrightnessChangeEventChannel.setStreamHandler(null)
        currentBrightnessChangeStreamHandler = null
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        currentBrightnessChangeEventChannel.setStreamHandler(null)
        currentBrightnessChangeStreamHandler = null
    }
}
