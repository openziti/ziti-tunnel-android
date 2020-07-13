/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.widget.HorizontalScrollView


class LockableScrollView: HorizontalScrollView {

    private var _isScrollable = true

    private var scrollTo = 0
    private var maxItem = 0
    private var activeItem = 0
    private var prevScrollX = 0f
    private var start = true
    private var itemWidth = 0
    private var currentScrollX: Float = 0.toFloat()

    constructor(context: Context) : super(context) {
        init(null, 0)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        init(attrs, defStyle)
    }

    private fun init(attrs: AttributeSet?, defStyle: Int) {
    }

    var scrollable: Boolean
        get() = this._isScrollable
        set(value) {
            this._isScrollable = value
        }

    override fun onTouchEvent(ev:MotionEvent):Boolean {
        if (ev.action==MotionEvent.ACTION_DOWN) {
            if (_isScrollable) return super.onTouchEvent(ev)
            else return _isScrollable
        } else {
            return super.onTouchEvent(ev)
        }
        if (_isScrollable) {
            if (ev.action==MotionEvent.ACTION_MOVE) {
                if (start) {
                    this.prevScrollX = x
                    start = false
                }
            }
            if (ev.action==MotionEvent.ACTION_UP) {
                Log.i("ek-tag", "Testing Movement")
                this.start = true
                this.currentScrollX = x
                var minFactor = itemWidth
                if ((this.prevScrollX - this.currentScrollX) > minFactor) {
                    if (activeItem < maxItem - 1)
                        activeItem = activeItem + 1

                } else if ((this.currentScrollX - this.prevScrollX) > minFactor) {
                    if (activeItem > 0)
                        activeItem = activeItem - 1
                }
                scrollTo = activeItem * itemWidth
                this.smoothScrollTo(scrollTo, 0)
                return true
            }
        }
    }

    override fun onInterceptTouchEvent(ev:MotionEvent):Boolean {
        return _isScrollable && super.onInterceptTouchEvent(ev)
    }

}