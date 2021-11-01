/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.debug

import android.os.Bundle
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import androidx.viewpager.widget.ViewPager
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import org.openziti.mobile.debug.SectionsPagerAdapter
import org.openziti.mobile.databinding.ActivityDebugInfoBinding

class DebugInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDebugInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val viewPager: ViewPager = binding.viewPager
        viewPager.adapter = SectionsPagerAdapter(this, supportFragmentManager)

        val tabs: TabLayout = binding.tabs
        tabs.setupWithViewPager(viewPager)
    }
}