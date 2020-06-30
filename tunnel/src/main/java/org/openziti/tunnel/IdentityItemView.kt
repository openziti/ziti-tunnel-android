/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import kotlinx.android.synthetic.main.identityitem.view.*

/**
 * TODO: document your custom view class.
 */
class IdentityItemView(context: AppCompatActivity, val ctxModel: ZitiContextModel) : RelativeLayout(context) {

    private var _name: String? = ""
    private var _count: Int = 0

    var idname: String
        get() = this._name.toString()
        set(value) {
            this._name = value
            IdentityName.text = this._name
        }

    var count: Int
        get() = this._count
        set(value) {
            this._count = value
            ServiceCount.text = this._count.toString()
        }

    private fun init(attrs: AttributeSet?, defStyle: Int) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.IdentityView, defStyle, 0)
        LayoutInflater.from(context).inflate(R.layout.identityitem, this, true)
        a.recycle()
    }

    init {
        init(null, 0)

        ctxModel.status().observe(context, Observer {
            IdentityName.text = ctxModel.name()
        })
    }
}
