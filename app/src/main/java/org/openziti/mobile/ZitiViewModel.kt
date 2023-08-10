/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import org.openziti.Ziti
import org.openziti.ZitiContext
import org.openziti.util.NetworkStats
import org.openziti.android.Ziti as ZitiAndroid

/**
 *
 *
 */
class ZitiViewModel: ViewModel() {
    private val contextData = MutableLiveData(Ziti.getContexts())
    private val networkStatsData = MutableLiveData<NetworkStats>()
    private val events = ZitiAndroid.identityEvents()
    private val observer = Observer<Any> {
        contextData.postValue(Ziti.getContexts())
    }

    init {
        events.observeForever(observer)
    }

    override fun onCleared() {
        events.removeObserver(observer)
    }

    fun contexts(): LiveData<Collection<ZitiContext>> = contextData
    fun stats(): LiveData<NetworkStats> = networkStatsData
}