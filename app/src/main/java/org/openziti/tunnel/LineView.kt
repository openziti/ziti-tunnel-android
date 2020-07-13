/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.RelativeLayout
import android.widget.Toast
import kotlinx.android.synthetic.main.line.view.*

/**
 * Service List Line Items
 */
class LineView : RelativeLayout {

    var _label: String? = ""
    var _value: String? = ""

    var label: String
        get() = this._label.toString()
        set(value) {
            this._label = value
            Label.text = this._label
        }

    var value: String
        get() = this._value.toString()
        set(value) {
            this._value = value
            Value.text = this._value
        }

    constructor(context: Context) : super(context) {
        init(null, 0)
        Label.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Label", Label.text.toString())
            clipboard.setPrimaryClip(clip)
            val content = Label.text.toString() + " has been copied to your clipboard"
            Toast.makeText(context, content, Toast.LENGTH_LONG).show()
        }
        Value.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Value", Value.text.toString())
            clipboard.setPrimaryClip(clip)
            val content = Value.text.toString() + " has been copied to your clipboard"
            Toast.makeText(context, content, Toast.LENGTH_LONG).show()
        }
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        init(attrs, defStyle)
    }

    private fun init(attrs: AttributeSet?, defStyle: Int) {
        LayoutInflater.from(context).inflate(R.layout.line, this, true)
    }
}
