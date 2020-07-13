/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.CompoundButton
import android.widget.RelativeLayout
import android.widget.Toast
import kotlinx.android.synthetic.main.card.view.*
import org.openziti.ZitiContext
import org.openziti.api.Service
import kotlin.math.abs

/**
 * TODO: document your custom view class.
 */
class CardView(context: Context, zitiCtx: ZitiContext) : RelativeLayout(context) {
    val identity = zitiCtx

    private var _name: String? = ""
    private var _network: String? = ""
    private var _status: String? = ""
    private var _enrollment: String? = ""
    private var _services: Array<Service> = arrayOf()
    private var _isOpen = false

    var onToggle: (() -> Unit)? = null


    var name: String
        get() = this._name.toString()
        set(value) {
            this._name = value
            IdName.text = this._name
            IdentityLabel.text = this._name
        }

    var isOpen: Boolean
        get() = this._isOpen
        set(value) {
            this._isOpen = value
        }

    var network: String
        get() = this._network.toString()
        set(value) {
            this._network = value
            NetworkName.text = this._network
        }

    var status: String
        get() = this._status.toString()
        set(value) {
            this._status = value
            if (this._status=="Active") {
                OnlineImage.visibility = View.VISIBLE
                OfflineImage.visibility = View.GONE
                OnOffSwitch.isChecked = true
            } else {
                OnlineImage.visibility = View.GONE
                OfflineImage.visibility = View.VISIBLE
                OnOffSwitch.isChecked = false
            }
            Status.text = this._status
        }

    var enrollment: String
        get() = this._enrollment.toString()
        set(value) {
            this._enrollment = value
            Enrollment.text = this._enrollment
        }

    var services: Array<Service>
        get() = this._services
        set(value) {
            this._services = value
            ServiceList.removeAllViews()
            ServiceCount.text = this._services.size.toString()
            for (service in this._services) {
                var line = LineView(context)
                line.label = service.name.toString()
                line.value = "service.dns?.hostname" // TODO
                ServiceList.addView(line)
            }
        }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        Log.i("ek-tag"," {$l} {$t} {$oldl} {$oldt} ")
    }

    fun getScreenWidth(): Int {
        return Resources.getSystem().displayMetrics.widthPixels
    }

    private var cardWidth = 0

    var x1: Float? = 0.toFloat()
    var x2: Float? = 0.toFloat()
    var velocityX1: Float? = 0.toFloat()
    var velocityX2: Float? = 0.toFloat()
    private var flingCount = 0
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        var action = event?.action
        if (x1 == 0.toFloat()) {
            x1 = event?.rawX
        } else {
            x2 = event?.rawX
            var distanceX: Float? = x1!!-x2!!
            var timeX: Float? = event?.downTime?.toFloat()

            velocityX2 = velocityX1 //v2 = previous v1
            velocityX1 = distanceX!!/timeX!!

            println("velocity 1: "+velocityX1?.toString())
            println("velocity 2: "+velocityX2?.toString()+"\n")
            var velocityDelta: Float? = velocityX2!!-velocityX1!!
            velocityX2 = 0.toFloat()
            //println("velocity delta: "+abs(velocityDelta!!).toString())
            if (velocityX1!! > 0.toFloat() && velocityX1!! == abs(velocityDelta!!)){ // fling left
                this.flingCount++
                println("fling left!  fling count is: "+this.flingCount)
                return true
            } else if (velocityX1!! < 0.toFloat() && abs(velocityX1!!) == velocityDelta!! && action == MotionEvent.ACTION_MOVE){ // fling right
                this.flingCount++
                println("fling right! fling count is: "+this.flingCount)
                return true
            } else if (distanceX == 0.toFloat() && action == MotionEvent.ACTION_UP && velocityX2==velocityDelta) { // tap or press
                println("tap somewhere on the screen")
                return true
            }
            x1 = 0.toFloat()
        }
        println("touch event called itself")
        return super.onTouchEvent(event)
    }

    private fun init(attrs: AttributeSet?, defStyle: Int) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.CardView, defStyle, 0)
        LayoutInflater.from(context).inflate(R.layout.card, this, true)
        ForgetButton.setOnClickListener {
            val id = this.identity
            id.let {
                // TODO Ziti.deleteIdentity(id)
                // Toast.makeText(context.applicationContext,id.name+" removed", Toast.LENGTH_LONG).show()
                Toast.makeText(context, "not  removed: TODO", Toast.LENGTH_LONG).show()

                OpenButton.callOnClick()
            }
        }

        OnOffSwitch.setOnCheckedChangeListener { button: CompoundButton, state: Boolean ->
            if (state) {
                // TODO zitiCtx.enable()
            } else {
                // TODO zitiCtx.disable()
            }
        }

        OpenButton.setOnClickListener {
            if (this.cardWidth==0) {
                this.cardWidth = this.width
            }
            var widthTo = this.cardWidth
            var widthFrom = getScreenWidth()

            if (this._isOpen) {
                Closer.visibility = View.GONE
                Opener.visibility = View.VISIBLE
                ServiceCount.visibility = View.VISIBLE
                ServiceCountBubble.visibility = View.VISIBLE
                OnOffSwitch.visibility = View.GONE
            } else {
                widthTo = getScreenWidth()
                widthFrom = this.cardWidth
                Opener.visibility = View.GONE
                Closer.visibility = View.VISIBLE
                ServiceCount.visibility = View.GONE
                ServiceCountBubble.visibility = View.GONE
                OnOffSwitch.visibility = View.VISIBLE
            }
            var slideAnimator = ValueAnimator.ofInt(widthFrom, widthTo).setDuration(500)

            slideAnimator.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
                override fun onAnimationUpdate(animation: ValueAnimator) {
                    var animatedValue = animation.animatedValue as Int
                    layoutParams.width = animatedValue
                    requestLayout()
                }
            })

            var animationSet = AnimatorSet()
            animationSet.interpolator = DecelerateInterpolator()
            animationSet.play(slideAnimator)
            animationSet.start()

            this._isOpen = !this._isOpen
            if (onToggle!=null) onToggle?.invoke()
        }
        a.recycle()
    }

    init {
        init(null, 0)
    }
}
