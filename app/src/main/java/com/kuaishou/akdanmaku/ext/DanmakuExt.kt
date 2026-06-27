/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.kuaishou.akdanmaku.ext

import android.graphics.Bitmap
import com.kuaishou.akdanmaku.data.DanmakuItem

/**
 * 数据相关扩展
 *
 * @author Xana
 * @since 2021-06-16
 */
val EMPTY_BITMAP: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

fun DanmakuItem.isTimeout(current: Long): Boolean =
  current - timePosition > duration

fun DanmakuItem.isLate(current: Long): Boolean =
  current - timePosition < 0

fun DanmakuItem.isOutside(current: Long): Boolean =
  isTimeout(current) || isLate(current)
