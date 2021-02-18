package tech.thdev.mediaprojectionexample.ui.util

import android.app.Service
import android.content.Context
import android.graphics.Point
import android.util.Size
import android.view.WindowManager

object DeviceUtil {
    fun getDeviceSize(context: Context): Size {
        val point = Point();
        val display =
            (context.getSystemService(Service.WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getRealSize(point)

        return Size(point.x, point.y)
    }
}