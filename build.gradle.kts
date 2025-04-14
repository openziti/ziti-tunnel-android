/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

plugins {
    alias(libs.plugins.android.app) apply(false)
    alias(libs.plugins.android.lib) apply(false)
    alias(libs.plugins.kotlin.android) apply(false)
    alias(libs.plugins.kotlin.compose) apply false
}
group = "org.openziti"
