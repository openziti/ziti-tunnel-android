/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.RelativeLayout
import android.widget.Toast
import org.openziti.mobile.databinding.LineBinding

/**
 * Service List Line Items
 */
class LineView(context: Context) : RelativeLayout(context) {

    var _label: String? = ""
    var _value: String? = ""

    private val binding = LineBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        binding.Label.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Label", binding.Label.text.toString())
            clipboard.setPrimaryClip(clip)
            val content = binding.Label.text.toString() + " has been copied to your clipboard"
            Toast.makeText(context, content, Toast.LENGTH_LONG).show()
        }
        binding.Value.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Value", binding.Value.text.toString())
            clipboard.setPrimaryClip(clip)
            val content = binding.Value.text.toString() + " has been copied to your clipboard"
            Toast.makeText(context, content, Toast.LENGTH_LONG).show()
        }
    }

    var label: String
        get() = this._label.toString()
        set(value) {
            this._label = value
            binding.Label.text = this._label
        }

    var value: String
        get() = this._value.toString()
        set(value) {
            this._value = value
            binding.Value.text = this._value
        }
}
