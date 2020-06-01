/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.openziti.Ziti
import org.openziti.ZitiContext
import org.openziti.util.NetworkStats

/**
 *
 *
 */
class ZitiViewModel: ViewModel() {
    val contextData = MutableLiveData(Ziti.getContexts())
    val networkStatsData = MutableLiveData<NetworkStats>()

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            contextData.postValue(Ziti.getContexts())
        }
    }

    init {
        LocalBroadcastManager.getInstance(org.openziti.android.Ziti.app).registerReceiver(receiver,
                IntentFilter().apply {
                    addAction(org.openziti.android.Ziti.IDENTITY_ADDED)
                    addAction(org.openziti.android.Ziti.IDENTITY_REMOVED)
                })
    }

    fun contexts(): LiveData<Collection<ZitiContext>> = contextData
    fun stats(): LiveData<NetworkStats> = networkStatsData
}