/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.content.res.Resources
import java.lang.StringBuilder
import java.time.Duration

fun Int.toDp(): Int = (this / Resources.getSystem().displayMetrics.density).toInt()
fun Int.toPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()

fun Duration.format(): String {
    val seconds = this.seconds
    val minutes = this.toMinutes()
    val hours = this.toHours()

    return "%02d:%02d:%02d".format(hours, minutes - hours * 60, seconds - minutes * 60)
}