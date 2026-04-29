/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openziti.mobile.databinding.DashboardBinding
import org.openziti.mobile.debug.DebugInfo
import org.openziti.mobile.fragments.AboutFragment
import org.openziti.mobile.fragments.AdvancedFragment
import org.openziti.mobile.fragments.IdentityDetailFragment
import org.openziti.mobile.model.TunnelModel

class ZitiMobileEdgeActivity : AppCompatActivity() {

    private lateinit var binding: DashboardBinding

    private val MainArea by lazy { binding.MainArea }
    private val FrameArea by lazy { binding.FrameArea }
    private val MainMenu by lazy { binding.MainMenu }

    private val OnButton by lazy { binding.OnButton }
    private val OffButton by lazy { binding.OffButton }
    private val DashboardButton by lazy { binding.DashboardButton }
    private val AdvancedButton by lazy { binding.AdvancedButton }
    private val AboutButton by lazy { binding.AboutButton }
    private val FeedbackButton by lazy { binding.FeedbackButton }
    private val SupportButton by lazy { binding.SupportButton }
    private val AddIdentityButton by lazy { binding.AddIdentityButton }
    private val AddIdentityLabel by lazy { binding.AddIdentityLabel }
    private val HamburgerButton by lazy { binding.HamburgerButton }
    private val HamburgerLabel by lazy { binding.HamburgerLabel }
    private val IdentityListing by lazy { binding.IdentityListing }
    private val StateButton by lazy { binding.StateButton }
    private val TimeConnected by lazy { binding.TimeConnected }
    private val MainLogo by lazy { binding.MainLogo }

    lateinit var prefs: SharedPreferences
    var isMenuOpen = false

    var state = "startActivity"
    val version = "${BuildConfig.VERSION_NAME}(${BuildConfig.GIT_COMMIT})"

    private val model: TunnelModel by lazy {
        (application as ZitiMobileEdgeApp).model
    }

    internal var vpn: ZitiVPNService.ZitiVPNBinder? = null
    internal val serviceConnection = object: ServiceConnection{
        override fun onServiceDisconnected(name: ComponentName?) {
            vpn = null
            updateTunnelState()
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpn = service as ZitiVPNService.ZitiVPNBinder
            updateTunnelState()
        }
    }

    fun launchUrl(url:String) {
        val openURL = Intent(Intent.ACTION_VIEW)
        openURL.data = url.toUri()
        startActivity(openURL)
    }

    private var duration = 300

    private val identityViews = mutableMapOf<String, IdentityItemView>()

    fun getScreenWidth(): Int {
        return Resources.getSystem().displayMetrics.widthPixels
    }

    fun getScreenHeight(): Int {
        return Resources.getSystem().displayMetrics.heightPixels
    }

    private fun toggleMenu() {
        val posTo = getScreenWidth()-(getScreenWidth()/3)
        val animatorSet = AnimatorSet()
        var scaleY = ObjectAnimator.ofFloat(binding.MainArea, "scaleY", .9f, 1.0f).setDuration(duration.toLong())
        var scaleX = ObjectAnimator.ofFloat(binding.MainArea, "scaleX", .9f, 1.0f).setDuration(duration.toLong())
        var fader = ObjectAnimator.ofFloat(FrameArea, "alpha", 1f, 0f).setDuration(duration.toLong())

        var animateTo = ObjectAnimator.ofFloat( MainArea,"translationX",posTo.toFloat(), 0f ).setDuration(duration.toLong())
        fader.interpolator = DecelerateInterpolator()
        animateTo.interpolator = DecelerateInterpolator()
        scaleY.interpolator = DecelerateInterpolator()
        scaleX.interpolator = DecelerateInterpolator()
        MainMenu.visibility = View.GONE
        state = "startActivity"
        if (!isMenuOpen) {
            state = "menu"
            MainMenu.visibility = View.VISIBLE
            animateTo = ObjectAnimator.ofFloat(MainArea, "translationX", 0f, posTo.toFloat()).setDuration(duration.toLong())
            scaleY = ObjectAnimator.ofFloat(MainArea, "scaleY", 1.0f, 0.9f).setDuration(duration.toLong())
            scaleX = ObjectAnimator.ofFloat(MainArea, "scaleX", 1.0f, 0.9f).setDuration(duration.toLong())
            fader = ObjectAnimator.ofFloat(FrameArea, "alpha", 0f, 1f).setDuration(duration.toLong())
        }
        animatorSet.play( animateTo ).with(scaleX).with(scaleY).with(fader)
        animatorSet.start()
        isMenuOpen = !isMenuOpen
    }

    private fun doBackPress() {
        if (!supportFragmentManager.popBackStackImmediate()) {
            if (isMenuOpen) toggleMenu()
            else finish()
        }
    }

    private var startPosition = 0f

    fun TurnOff() {
        OnButton.visibility = View.GONE
        OffButton.visibility = View.VISIBLE
        TimeConnected.visibility = View.INVISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DashboardBinding.inflate(layoutInflater)

        setContentView(binding.root)
        this.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        // Setup Screens
        this.startPosition = getScreenHeight().toDp()-130.toDp().toFloat()

        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = doBackPress()
        })

        val vb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vbm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vbm.defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        val vpnPrepare = registerForActivityResult(StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                startZitiVPN()
            } else {
                // TODO: maybe toast
            }
        }
        // Dashboard Button Actions
        OffButton.setOnClickListener {
            if (vb.hasVibrator()) vb.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            val intent = VpnService.prepare(applicationContext)
            if (intent != null) {
                vpnPrepare.launch(intent)
            } else {
                startZitiVPN()
            }
            OnButton.visibility = View.VISIBLE
            OffButton.visibility = View.GONE
            TimeConnected.visibility = View.VISIBLE
        }

        OnButton.setOnClickListener {
            if (vb.hasVibrator()) vb.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            stopZitiVPN()
            TurnOff()
        }

        // Menu Button Actions
        DashboardButton.setOnClickListener {
            toggleMenu()
        }
        MainLogo.setOnClickListener {
            toggleMenu()
        }
        AboutButton.setOnClickListener {
            toggleMenu()
            supportFragmentManager.commit {
                add<AboutFragment>(R.id.fragment_container_view, "about")
                addToBackStack("about")
            }
        }
        AdvancedButton.setOnClickListener {
            toggleMenu()
            supportFragmentManager.commit {
                add<AdvancedFragment>(R.id.fragment_container_view, "advanced")
                addToBackStack("advanced")
            }
        }

        FeedbackButton.setOnClickListener {
            startActivity(Intent.createChooser(DebugInfo.feedbackIntent(app = ZitiMobileEdgeApp.app),
                "Send Email"))
        }
        SupportButton.setOnClickListener {
            launchUrl("https://support.netfoundry.io")
        }
        AddIdentityButton.setOnClickListener {
            startActivity(getEnrollmentIntent(application))
        }
        AddIdentityLabel.setOnClickListener {
            startActivity(getEnrollmentIntent(application))
        }
        HamburgerButton.setOnClickListener {
            toggleMenu()
        }
        HamburgerLabel.setOnClickListener {
            toggleMenu()
        }

        model.identities().observe(this) { contextList ->
            val seen = mutableSetOf<String>()
            for (ctx in contextList) {
                seen += ctx.id
                if (identityViews[ctx.id] == null) {
                    val identityitem = IdentityItemView(this, ctx)
                    identityitem.setOnClickListener {
                        supportFragmentManager.commit {
                            add<IdentityDetailFragment>(R.id.fragment_container_view, "identity",
                                Bundle(1).apply{
                                    putString(IdentityDetailFragment.ID, ctx.id)
                                }
                            )
                            addToBackStack("identity")
                        }
                    }
                    identityViews[ctx.id] = identityitem
                    IdentityListing.addView(identityitem)
                }
            }
            (identityViews.keys - seen).forEach { id ->
                identityViews.remove(id)?.let {
                    IdentityListing.removeView(it)
                }
            }

            if (contextList.isEmpty()) {
                TurnOff()
                OffButton.isClickable = false
                StateButton.imageAlpha = 144
            } else {
                OffButton.isClickable = true
                StateButton.imageAlpha = 255
            }
        }


        prefs = getSharedPreferences("ziti-vpn", MODE_PRIVATE)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateConnectedTime()
                    delay(1000)
                }
            }
        }
    }

    private fun updateConnectedTime() {
        val uptime = vpn?.getUptime()?.format() ?: ""
        TimeConnected.post {
            TimeConnected.text = uptime
        }
    }

    override fun onPause() {
        unbindService(serviceConnection)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        bindService(Intent(applicationContext, ZitiVPNService::class.java), serviceConnection,
            BIND_AUTO_CREATE
        )

        updateTunnelState()
    }

    private fun updateTunnelState() {
        val on = vpn?.isVPNActive() ?: false
        updateConnectedView(on)
    }

    private fun updateConnectedView(on: Boolean) {
        OnButton.visibility = if (on) View.VISIBLE else View.GONE
        OffButton.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun stopZitiVPN() {
        startService(Intent(this, ZitiVPNService::class.java).setAction("stop"))
    }

    private fun startZitiVPN() {
        startService(Intent(this, ZitiVPNService::class.java).setAction("start"))
    }

}
