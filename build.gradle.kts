/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.

plugins {
    alias(libs.plugins.android.app) apply(false)
    alias(libs.plugins.android.lib) apply(false)
    alias(libs.plugins.kotlin.android) apply(false)
}
group = "org.openziti"
