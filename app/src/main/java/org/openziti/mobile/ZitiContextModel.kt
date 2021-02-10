/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.openziti.ZitiContext
import org.openziti.android.Ziti
import org.openziti.api.Service

/**
 *
 */
class ZitiContextModel(val ctx: ZitiContext): ViewModel() {

    private val statusSub = ctx.statusUpdates()
    private val serviceSub = ctx.serviceUpdates()

    private val statusLive = MutableLiveData(ctx.getStatus())
    private val nameLive = MutableLiveData(ctx.name())

    private val servicesMap = sortedMapOf<String,Service>()
    private val servicesData = MutableLiveData<Collection<Service>>(listOf())

    init {
        GlobalScope.launch {
            statusSub.collect {
                statusLive.postValue(it)
                if (it is ZitiContext.Status.Active)
                    nameLive.postValue(ctx.getId()?.name ?: ctx.name())

            }
        }

        GlobalScope.launch {
            serviceSub.collect {
                when(it.type) {
                    ZitiContext.ServiceUpdate.Available -> {
                        servicesMap.put(it.service.name, it.service)
                    }
                    ZitiContext.ServiceUpdate.Unavailable -> {
                        servicesMap.remove(it.service.name)
                    }
                    ZitiContext.ServiceUpdate.ConfigurationChange -> {}
                }

                servicesData.postValue(servicesMap.values.toList())
            }
        }
    }

    class Factory(val ctx: ZitiContext): ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ZitiContextModel(ctx) as T
        }
    }

    fun name(): LiveData<String> = nameLive
    fun status(): LiveData<ZitiContext.Status> = statusLive
    fun services(): LiveData<Collection<Service>> = servicesData

    fun delete() {
        Ziti.deleteIdentity(ctx)
    }

    override fun onCleared() {
    }
}