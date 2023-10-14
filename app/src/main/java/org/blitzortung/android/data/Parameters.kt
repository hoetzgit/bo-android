/*

   Copyright 2015 Andreas Würl

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

package org.blitzortung.android.data

import org.blitzortung.android.data.provider.GLOBAL_REGION
import org.blitzortung.android.data.provider.LOCAL_REGION
import java.io.Serializable

data class Parameters(
    val region: Int = -1,
    val rasterBaselength: Int = 0,
    val interval: TimeInterval = TimeInterval(),
    val countThreshold: Int = 0,
    val localReference: LocalReference? = null
) : Serializable {

    val intervalDuration: Int
        get() = interval.duration

    val intervalOffset: Int
        get() = interval.offset

    val isGlobal: Boolean = region == GLOBAL_REGION

    val isLocal: Boolean = region == LOCAL_REGION

    fun isRealtime(): Boolean = interval.isRealtime()

    fun rewInterval(history: History): Parameters = copy(interval = interval.rewInterval(history))

    fun ffwdInterval(history: History): Parameters = copy(interval = interval.ffwdInterval(history))

    fun animationStep(history: History): Parameters = copy(interval = interval.animationStep(history))

    fun withIntervalOffset(offset: Int, history: History): Parameters = copy(interval = interval.withOffset(offset, history))

    fun withPosition(position: Int, history: History): Parameters {
        val offset = (-intervalMaxPosition(history) + position) * history.timeIncrement
        return copy(interval = interval.withOffset(offset, history))
    }

    fun goRealtime(): Parameters = copy(interval = interval.goRealtime())

    fun withIntervalDuration(intervalDuration: Int): Parameters {
        return copy(interval = interval.copy(duration = intervalDuration))
    }

    fun intervalPosition(history: History): Int =
        if (history.timeIncrement != 0) -interval.offset / history.timeIncrement else 0

    fun intervalMaxPosition(history: History): Int =
        if (history.timeIncrement != 0) (history.range - interval.duration) / history.timeIncrement else 0

}

data class LocalReference(
    val x: Int,
    val y: Int
) : Serializable

data class History(
    val timeIncrement: Int = DEFAULT_OFFSET_INCREMENT,
    val range: Int = MAX_HISTORY_RANGE
) : Serializable {
    companion object {
        const val DEFAULT_OFFSET_INCREMENT = 30
        const val MAX_HISTORY_RANGE = 24 * 60
    }
}