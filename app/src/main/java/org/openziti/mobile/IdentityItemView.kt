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
import androidx.lifecycle.Observer
import org.openziti.mobile.databinding.IdentityitemBinding
import org.openziti.mobile.model.Identity
import org.openziti.tunnel.Service
import timber.log.Timber as Log


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
        binding = IdentityitemBinding.inflate(LayoutInflater.from(context), this, true)
        binding.IdToggleSwitch.isSaveEnabled = false

        offline = ResourcesCompat.getDrawable(context.resources, R.drawable.offline, null)!!
        bubble = ResourcesCompat.getDrawable(context.resources, R.drawable.bubble, null)!!
    }

    @SuppressLint("DefaultLocale")
    fun setModel(ztx: Identity) {
        ctxModel = ztx
        val enabled = ctxModel.enabled().value ?: false
        binding.IdToggleSwitch.isChecked = enabled
        binding.IdToggleSwitch.setOnCheckedChangeListener { _, state ->
            ctxModel.setEnabled(state)
        }

    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d("wiring up ${ctxModel.name.value}")
        ctxModel.controllers().observe(owner, controllerObserver)
        ctxModel.name().observe(owner, nameObserver)
        ctxModel.authState().observe(owner, authObserver)
        ctxModel.services().observe(owner, servicesObserver)
        ctxModel.enabled().observe(owner, enabledObserver)
        ctxModel.refresh()
    }
    override fun onDetachedFromWindow() {
        Log.d("un-wiring ${ctxModel.name.value}")
        ctxModel.controllers().removeObserver(controllerObserver)
        ctxModel.name().removeObserver(nameObserver)
        ctxModel.authState().removeObserver(authObserver)
        ctxModel.services().removeObserver(servicesObserver)
        ctxModel.enabled().removeObserver(enabledObserver)
        super.onDetachedFromWindow()
    }

    val controllerObserver = Observer<List<String>> {
        binding.IdentityServer.text = it.firstOrNull() ?: "unknown"
    }

    val nameObserver = Observer<String?> {
        if (!it.isNullOrEmpty()) {
            binding.IdentityName.text = it
        }
    }

    @SuppressLint("DefaultLocale")
    val authObserver =  Observer<Identity.AuthState> {
        when (it) {
            Identity.AuthNone -> {
                binding.ServiceCountBubble.setImageDrawable(offline)
                binding.ServicesStatus.text = context.getString(R.string.offline)
                binding.ServiceCount.text = ""
            }

            Identity.Authenticated -> {
                binding.ServicesStatus.setText(R.string.services)
                binding.ServiceCountBubble.setImageDrawable(bubble)
                binding.ServiceCount.text =
                    String.format("%d", ctxModel.services().value?.size ?: 0)
            }

            is Identity.AuthJWT -> {
                binding.ServiceCountBubble.setImageDrawable(offline)
                binding.ServicesStatus.text = context.getString(R.string.ext_login)
                binding.ServiceCount.text = ""
            }
        }
    }

    @SuppressLint("DefaultLocale")
    val servicesObserver = Observer<List<Service>> {
        if (ctxModel.authState().value is Identity.Authenticated) {
            binding.ServiceCount.text = String.format("%d", it.size)
        }
    }

    val enabledObserver = Observer<Boolean> {
        binding.IdToggleSwitch.isChecked = it
        binding.IdToggleSwitch.text = if(it) "enabled" else "disabled"
    }
}
