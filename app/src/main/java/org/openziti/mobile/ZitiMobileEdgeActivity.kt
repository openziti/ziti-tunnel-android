/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
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
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.ConfigurationCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.viewbinding.ViewBinding
import org.openziti.mobile.databinding.DashboardBinding
import org.openziti.mobile.debug.DebugInfo
import org.openziti.mobile.fragments.AboutFragment
import org.openziti.mobile.fragments.AdvancedFragment
import org.openziti.mobile.fragments.IdentityDetailFragment
import java.util.Timer
import java.util.TimerTask

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
    private val DownloadSpeed by lazy { binding.DownloadSpeed }
    private val DownloadMbps by lazy { binding.DownloadMbps }
    private val UploadMbps by lazy { binding.UploadMbps }
    private val UploadSpeed by lazy { binding.UploadSpeed }
    private val TimeConnected by lazy { binding.TimeConnected }
    private val MainLogo by lazy { binding.MainLogo }

    lateinit var prefs: SharedPreferences
    var isMenuOpen = false

    var state = "startActivity"
    val version = "${BuildConfig.VERSION_NAME}(${BuildConfig.GIT_COMMIT})"

    private val model: TunnelModel by viewModels { TunnelModel.Factory }

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
        openURL.data = Uri.parse(url)
        startActivity(openURL)
    }

    var duration = 300
    var offScreenX = 0
    var offScreenY = 0
    var openY = 0

    fun getScreenWidth(): Int {
        return Resources.getSystem().displayMetrics.widthPixels
    }

    fun getScreenHeight(): Int {
        return Resources.getSystem().displayMetrics.heightPixels
    }

    private fun toggleMenu() {
        val posTo = getScreenWidth()-(getScreenWidth()/3)
        var animatorSet = AnimatorSet()
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

    private fun toggleSlide(b: ViewBinding, newState: String) = toggleSlide(b.root, newState)
    private fun toggleSlide(view:View, newState:String) {
        try {
            val inputManager:InputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputManager.hideSoftInputFromWindow(currentFocus!!.windowToken, 0)
        } catch (e:Exception) {}
        var fader = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f).setDuration(duration.toLong())
        var animateTo = ObjectAnimator.ofFloat( view,"translationX", offScreenX.toFloat(), 0f ).setDuration(duration.toLong())
        fader.interpolator = DecelerateInterpolator()
        animateTo.interpolator = DecelerateInterpolator()
        state = newState
        if (view.x==0f) {
            fader = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f).setDuration(duration.toLong())
            animateTo = ObjectAnimator.ofFloat( view,"translationX", 0f, offScreenX.toFloat() ).setDuration(duration.toLong())
        }
        var animatorSet = AnimatorSet()
        animatorSet.play( animateTo ).with(fader)
        animatorSet.start()
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

        offScreenX = getScreenWidth()+50
        offScreenY = getScreenHeight()-370

        // Setup Screens
        openY = offScreenY
        this.startPosition = getScreenHeight().toDp()-130.toDp().toFloat()

        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = doBackPress()
        })

        val vb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vbm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vbm.defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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

        val timer = Timer()
        val task = object: TimerTask() {
            override fun run() {
                val uptime = vpn?.getUptime()?.format() ?: ""
                TimeConnected.post {
                    TimeConnected.text = uptime
                }
            }
        }
        timer.schedule(task, 0, 1000)

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
            binding.IdentityListing.removeAllViews()
            var index = 0
            for (ctx in contextList) {

                val identityitem = IdentityItemView(this).apply { setModel(ctx) }

                identityitem.setOnClickListener {
                    supportFragmentManager.commit {
                        add<IdentityDetailFragment>(R.id.fragment_container_view, "identity",
                            bundleOf(IdentityDetailFragment.ID to ctx.id))
                        addToBackStack("identity")
                    }

                }
                IdentityListing.addView(identityitem)
                index++
            }
            //IdentityCount.text = index.toString()
            if (index == 0) {
                TurnOff()
                //OffButton.getBackground().setAlpha(45)
                OffButton.isClickable = false
                StateButton.imageAlpha = 144
            } else {
                //OffButton.getBackground().setAlpha(100)
                OffButton.isClickable = true
                StateButton.imageAlpha = 255
            }
        }


        prefs = getSharedPreferences("ziti-vpn", Context.MODE_PRIVATE)
        //checkAppList()

        //bindService(Intent(applicationContext, ZitiVPNService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        super.onPause()
        unbindService(serviceConnection)
    }

    override fun onResume() {
        super.onResume()
        bindService(Intent(applicationContext, ZitiVPNService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        updateTunnelState()

        model.stats().observe(this) {
            setSpeed(it.down, DownloadSpeed, DownloadMbps)
            setSpeed(it.up, UploadSpeed, UploadMbps)
        }

    }

    private fun updateTunnelState() {
        val on = vpn?.isVPNActive() ?: false
        updateConnectedView(on)
    }

    private fun updateConnectedView(on: Boolean) {
        OnButton.visibility = if (on) View.VISIBLE else View.GONE
        OffButton.visibility = if (on) View.GONE else View.VISIBLE
    }

    val MB = 1024 * 1024
    val KB = 1024

    fun setSpeed(rate: Double, speed: TextView, label: TextView) {
        val r: Double
        val l: String
        when {
            rate * 8 > MB -> {
                r = (rate * 8) / (1024 * 1024)
                l = "Mbps"
            }
            rate * 8 > KB -> {
                r = (rate * 8) / KB
                l = "Kbps"
            }
            else -> {
                r = rate * 8
                l = "bps"
            }
        }

        speed.text = String.format(
            ConfigurationCompat.getLocales(resources.configuration)[0], "%.1f", r)
        label.text = l
    }

    private fun stopZitiVPN() {
        startService(Intent(this, ZitiVPNService::class.java).setAction("stop"))
    }

    private fun startZitiVPN() {
        startService(Intent(this, ZitiVPNService::class.java).setAction("start"))
    }

}
