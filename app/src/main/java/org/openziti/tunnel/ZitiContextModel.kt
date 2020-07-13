/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
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

    private val servicesMap = sortedMapOf<String,Service>()
    private val servicesData = MutableLiveData<Collection<Service>>(listOf())

    init {
        GlobalScope.launch {
            statusSub.consumeAsFlow().collect {
                statusLive.postValue(it)
            }
        }

        GlobalScope.launch {
            serviceSub.consumeAsFlow().collect {
                when(it.type) {
                    ZitiContext.ServiceUpdate.Available -> {
                        servicesMap.put(it.service.name, it.service)
                    }
                    ZitiContext.ServiceUpdate.Unavailable -> {
                        servicesMap.remove(it.service.name)
                    }
                    ZitiContext.ServiceUpdate.ConfigurationChange -> {}
                }

                servicesData.postValue(servicesMap.values)
            }
        }
    }

    class Factory(val ctx: ZitiContext): ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ZitiContextModel(ctx) as T
        }
    }

    fun name(): String {
        return ctx.getId()?.name ?: ctx.name()
    }

    fun status(): LiveData<ZitiContext.Status> {
        return statusLive
    }

    fun services(): LiveData<Collection<Service>> {
        return servicesData
    }

    fun delete() {
        Ziti.deleteIdentity(ctx)
    }

    override fun onCleared() {
        statusSub.cancel()
        serviceSub.cancel()
    }
}