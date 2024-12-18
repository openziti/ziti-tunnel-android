/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile
import android.content.Context
import android.view.LayoutInflater
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import org.openziti.mobile.databinding.IdentityitemBinding
import org.openziti.mobile.model.TunnelModel

/**
 * TODO: document your custom view class.
 */
class IdentityItemView(context: Context) : RelativeLayout(context) {

    private var _count: Int = 0
    private var _isOn: Boolean = false
    lateinit var ctxModel: TunnelModel.TunnelIdentity
    val owner = context as AppCompatActivity
    val binding: IdentityitemBinding

    var isOn:Boolean
        get() = this._isOn
        set(value) {
            this._isOn = value
            binding.IdToggleSwitch.isChecked = value
            binding.StatusLabel.text = if(value) "enabled" else "disabled"
        }

    init {
        val a = context.obtainStyledAttributes(null, R.styleable.IdentityItemView, 0, 0)
        binding = IdentityitemBinding.inflate(LayoutInflater.from(context), this, true)
        // LayoutInflater.from(context).inflate(R.layout.identityitem, this, true)
        a.recycle()
    }

    fun setModel(ztx: TunnelModel.TunnelIdentity) {
        ztx.refresh()

        ctxModel = ztx
        ztx.controllers().observe(owner) {
            binding.IdentityServer.text = it.firstOrNull() ?: "unknown"
        }

        ctxModel.name().observe(owner) {
            binding.IdentityName.text = it
        }

        ctxModel.services().observe(owner) {
            binding.ServiceCount.text = String.format("%d", it.size)
        }
        ctxModel.enabled().observe(owner) { state ->
            isOn = state
        }

        binding.IdToggleSwitch.setOnCheckedChangeListener { _, state ->
            ctxModel.setEnabled(state)
        }

    }
}
