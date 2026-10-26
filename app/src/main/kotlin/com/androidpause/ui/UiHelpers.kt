package com.androidpause.ui

import android.content.Context
import android.text.format.DateFormat
import java.time.LocalTime
import java.util.Calendar

/** Android's locale-aware time format also honors the user's 12/24-hour preference. */
fun formatTime(
    context: Context,
    time: LocalTime,
): String {
    val calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, time.hour)
            set(Calendar.MINUTE, time.minute)
        }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}
