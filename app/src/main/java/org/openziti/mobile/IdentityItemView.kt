/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import org.openziti.mobile.databinding.IdentityitemBinding
import org.openziti.mobile.model.Identity

/**
 * TODO: document your custom view class.
 */
class IdentityItemView(context: Context) : RelativeLayout(context) {

    private lateinit var ctxModel: Identity
    private val owner = context as AppCompatActivity
    private val binding: IdentityitemBinding
    private val offline: Drawable
    private val bubble: Drawable

    init {
        val a = context.obtainStyledAttributes(null, R.styleable.IdentityItemView, 0, 0)
        binding = IdentityitemBinding.inflate(LayoutInflater.from(context), this, true)

        offline = ResourcesCompat.getDrawable(context.resources, R.drawable.offline, null)!!
        bubble = ResourcesCompat.getDrawable(context.resources, R.drawable.bubble, null)!!
        a.recycle()
    }

    @SuppressLint("DefaultLocale")
    fun setModel(ztx: Identity) {
        ztx.refresh()

        ctxModel = ztx
        ztx.controllers().observe(owner) {
            binding.IdentityServer.text = it.firstOrNull() ?: "unknown"
        }

        ctxModel.name().observe(owner) {
            if (!it.isNullOrEmpty()) {
                binding.IdentityName.text = it
            }
        }

        ctxModel.authState().observe(owner) {
            when (it) {
                Identity.AuthNone -> {
                    binding.ServiceCountBubble.setImageDrawable(offline)
                    binding.ServicesStatus.text = context.getString(R.string.offline)
                    binding.ServiceCount.text = ""
                }
                Identity.Authenticated -> {
                    binding.ServicesStatus.setText(R.string.services)
                    binding.ServiceCountBubble.setImageDrawable(bubble)
                    ctxModel.services().observe(owner) { services ->
                        binding.ServiceCount.text = String.format("%d", services.size)
                    }
                }
                is Identity.AuthJWT -> {
                    binding.ServiceCountBubble.setImageDrawable(offline)
                    binding.ServicesStatus.text = context.getString(R.string.ext_login)
                    binding.ServiceCount.text = ""
                }
            }
        }

        ctxModel.services().observe(owner) {
            binding.ServiceCount.text = String.format("%d", it.size)
        }
        ctxModel.enabled().observe(owner) { state ->
            binding.IdToggleSwitch.isChecked = state
            binding.IdToggleSwitch.text = if(state) "enabled" else "disabled"
        }

        binding.IdToggleSwitch.setOnCheckedChangeListener { _, state ->
            ctxModel.setEnabled(state)
        }
    }
}
