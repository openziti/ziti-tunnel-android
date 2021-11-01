/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.debug

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class PageViewModel : ViewModel() {

    private val textLD = MutableLiveData<String>()
    val text: LiveData<String> = textLD

    fun setText(text: String) {
        textLD.value = text
    }
}