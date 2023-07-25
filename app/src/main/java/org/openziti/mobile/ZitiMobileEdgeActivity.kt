/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import kotlinx.android.synthetic.main.about.BackButton
import kotlinx.android.synthetic.main.about.PrivacyButton
import kotlinx.android.synthetic.main.about.TermsButton
import kotlinx.android.synthetic.main.about.ThirdButton
import kotlinx.android.synthetic.main.about.Version
import kotlinx.android.synthetic.main.advanced.BackAdvancedButton
import kotlinx.android.synthetic.main.advanced.LogsButton
import kotlinx.android.synthetic.main.advanced.TunnelButton
import kotlinx.android.synthetic.main.configuration.BackConfigButton
import kotlinx.android.synthetic.main.configuration.BackConfigButton2
import kotlinx.android.synthetic.main.configuration.DNSInput
import kotlinx.android.synthetic.main.configuration.IPInput
import kotlinx.android.synthetic.main.configuration.MTUInput
import kotlinx.android.synthetic.main.configuration.SubNetInput
import kotlinx.android.synthetic.main.dashboard.AboutButton
import kotlinx.android.synthetic.main.dashboard.AboutPage
import kotlinx.android.synthetic.main.dashboard.AddIdentityButton
import kotlinx.android.synthetic.main.dashboard.AddIdentityLabel
import kotlinx.android.synthetic.main.dashboard.AdvancedButton
import kotlinx.android.synthetic.main.dashboard.AdvancedPage
import kotlinx.android.synthetic.main.dashboard.ConfigPage
import kotlinx.android.synthetic.main.dashboard.DashboardButton
import kotlinx.android.synthetic.main.dashboard.DownloadMbps
import kotlinx.android.synthetic.main.dashboard.DownloadSpeed
import kotlinx.android.synthetic.main.dashboard.FeedbackButton
import kotlinx.android.synthetic.main.dashboard.FrameArea
import kotlinx.android.synthetic.main.dashboard.HamburgerButton
import kotlinx.android.synthetic.main.dashboard.HamburgerLabel
import kotlinx.android.synthetic.main.dashboard.IdentityDetailsPage
import kotlinx.android.synthetic.main.dashboard.IdentityListing
import kotlinx.android.synthetic.main.dashboard.IdentityPage
import kotlinx.android.synthetic.main.dashboard.LogPage
import kotlinx.android.synthetic.main.dashboard.LogsPage
import kotlinx.android.synthetic.main.dashboard.MainArea
import kotlinx.android.synthetic.main.dashboard.MainLogo
import kotlinx.android.synthetic.main.dashboard.MainMenu
import kotlinx.android.synthetic.main.dashboard.OffButton
import kotlinx.android.synthetic.main.dashboard.OnButton
import kotlinx.android.synthetic.main.dashboard.StateButton
import kotlinx.android.synthetic.main.dashboard.SupportButton
import kotlinx.android.synthetic.main.dashboard.TimeConnected
import kotlinx.android.synthetic.main.dashboard.UploadMbps
import kotlinx.android.synthetic.main.dashboard.UploadSpeed
import kotlinx.android.synthetic.main.identities.BackIdentityButton
import kotlinx.android.synthetic.main.identity.BackIdentityDetailsButton
import kotlinx.android.synthetic.main.identity.IdDetailForgetButton
import kotlinx.android.synthetic.main.identity.IdDetailServicesList
import kotlinx.android.synthetic.main.identity.IdDetailsEnrollment
import kotlinx.android.synthetic.main.identity.IdDetailsNetwork
import kotlinx.android.synthetic.main.identity.IdDetailsStatus
import kotlinx.android.synthetic.main.identity.IdIdentityDetailName
import kotlinx.android.synthetic.main.identity.IdOnOffSwitch
import kotlinx.android.synthetic.main.log.BackToLogsButton
import kotlinx.android.synthetic.main.log.BackToLogsButton2
import kotlinx.android.synthetic.main.log.CopyLogButton
import kotlinx.android.synthetic.main.log.LogDetails
import kotlinx.android.synthetic.main.log.LogTypeTitle
import kotlinx.android.synthetic.main.logs.ApplicationLogsButton
import kotlinx.android.synthetic.main.logs.BackLogsButton
import kotlinx.android.synthetic.main.logs.LogsLabel
import kotlinx.android.synthetic.main.logs.PacketLogsButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.openziti.ZitiContext
import org.openziti.android.Ziti
import org.openziti.mobile.databinding.AboutBinding
import org.openziti.mobile.databinding.MainBinding
import org.openziti.mobile.debug.DebugInfoActivity
import java.util.Timer
import java.util.TimerTask


class ZitiMobileEdgeActivity : AppCompatActivity() {

    private lateinit var binding: MainBinding
    private lateinit var about: AboutBinding

    lateinit var prefs: SharedPreferences
    val systemId: Int by lazy {
        this.packageManager?.getApplicationInfo("android", PackageManager.GET_META_DATA)?.uid ?: 0
    }
    var isMenuOpen = false

    var ipAddress = "169.254.0.1"
    var subnet = "255.255.255.0"
    var mtu = "4000"
    var dns = "169.254.0.2"
    var state = "startActivity"
    var log_application = ""
    var log_tunneler = ""
    val version = "${BuildConfig.VERSION_NAME}(${BuildConfig.GIT_COMMIT})"

    lateinit var contextViewModel: ZitiViewModel
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
    var isOpen = false

    fun getScreenWidth(): Int {
        return Resources.getSystem().displayMetrics.widthPixels
    }

    fun getScreenHeight(): Int {
        return Resources.getSystem().displayMetrics.heightPixels
    }

    private fun toggleMenu() {
        val posTo = getScreenWidth()-(getScreenWidth()/3)
        var animatorSet = AnimatorSet()
        var scaleY = ObjectAnimator.ofFloat(MainArea, "scaleY", .9f, 1.0f).setDuration(duration.toLong())
        var scaleX = ObjectAnimator.ofFloat(MainArea, "scaleX", .9f, 1.0f).setDuration(duration.toLong())
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

    private fun toggleSlide(view:View, newState:String) {
        try {
            val inputManager:InputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputManager.hideSoftInputFromWindow(currentFocus!!.windowToken, InputMethodManager.SHOW_FORCED)
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

    override fun onBackPressed() {
        if (state=="menu") toggleMenu()
        else if (state=="about") toggleSlide(AboutPage, "menu")
        else if (state=="advanced") toggleSlide(AdvancedPage, "menu")
        else if (state=="config") toggleSlide(ConfigPage, "advanced")
        else if (state=="identity") toggleSlide(ConfigPage, "identities")
        else super.onBackPressed()
    }

    private var startPosition = 0f


    fun TurnOff() {
        OnButton.visibility = View.GONE
        OffButton.visibility = View.VISIBLE
        TimeConnected.visibility = View.INVISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainBinding.inflate(layoutInflater)

        setContentView(R.layout.main)
        this.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        offScreenX = getScreenWidth()+50
        offScreenY = getScreenHeight()-370
        Version.text = "Version: $version"

        // Setup Screens
        AboutPage.visibility = View.VISIBLE
        AdvancedPage.visibility = View.VISIBLE
        ConfigPage.visibility = View.VISIBLE
        LogsPage.visibility = View.VISIBLE
        LogPage.visibility = View.VISIBLE
        IdentityDetailsPage.visibility = View.VISIBLE
        IdentityPage.visibility = View.VISIBLE
        AboutPage.alpha = 0f
        AdvancedPage.alpha = 0f
        ConfigPage.alpha = 0f
        LogsPage.alpha = 0f
        LogPage.alpha = 0f
        IdentityPage.alpha = 0f
        IdentityDetailsPage.alpha = 0f
        AboutPage.x = offScreenX.toFloat()
        AdvancedPage.x = offScreenX.toFloat()
        ConfigPage.x = offScreenX.toFloat()
        LogsPage.x = offScreenX.toFloat()
        LogPage.x = offScreenX.toFloat()
        IdentityPage.x = offScreenX.toFloat()
        IdentityDetailsPage.x = offScreenX.toFloat()
        openY = offScreenY
        this.startPosition = getScreenHeight().toDp()-130.toDp().toFloat()

        //this.startPosition = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, yLoc, getResources().getDisplayMetrics())
        //IdentityArea.y = 10.toDp().toFloat() //this.startPosition

        IPInput.text = ipAddress
        SubNetInput.text = subnet
        MTUInput.text = mtu
        DNSInput.text = dns

        // Dashboard Button Actions
        OffButton.setOnClickListener {
            val vb = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vb.hasVibrator()) vb.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            val intent = VpnService.prepare(applicationContext)
            if (intent != null) {
                startActivityForResult(intent, 10169)
            } else {
                onActivityResult(10169, RESULT_OK, null)
            }
            OnButton.visibility = View.VISIBLE
            OffButton.visibility = View.GONE
            TimeConnected.visibility = View.VISIBLE
        }
        OnButton.setOnClickListener {
            val vb = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vb.hasVibrator()) vb.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            onActivityResult(10168, RESULT_OK, null)
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
            toggleSlide(AboutPage, "about")
        }
        AdvancedButton.setOnClickListener {
            toggleSlide(AdvancedPage, "advanced")
        }

        LogsLabel.isLongClickable = true
        LogsLabel.setOnLongClickListener {
            val intent = Intent(it.context, DebugInfoActivity::class.java)
            startActivity(intent)
            true
        }

        FeedbackButton.setOnClickListener {
            startActivity(Intent.createChooser(Ziti.sendFeedbackIntent(), "Send Email"))
        }
        SupportButton.setOnClickListener {
            launchUrl("https://support.netfoundry.io")
        }
        AddIdentityButton.setOnClickListener {
            startActivity(Ziti.getEnrollmentIntent())
        }
        AddIdentityLabel.setOnClickListener {
            startActivity(Ziti.getEnrollmentIntent())
        }
        HamburgerButton.setOnClickListener {
            toggleMenu()
        }
        HamburgerLabel.setOnClickListener {
            toggleMenu()
        }

        // About Button Actions
        PrivacyButton.setOnClickListener {
            launchUrl("https://netfoundry.io/privacy-policy/")
        }
        TermsButton.setOnClickListener {
            launchUrl("https://netfoundry.io/terms/")
        }
        ThirdButton.setOnClickListener {
            launchUrl("https://netfoundry.io/third-party")
        }

        // Back Buttons
        BackButton.setOnClickListener {
            toggleSlide(AboutPage, "menu")
        }
        BackIdentityButton.setOnClickListener {
            toggleSlide(IdentityPage, "menu")
        }
        BackAdvancedButton.setOnClickListener {
            toggleSlide(AdvancedPage, "menu")
        }
        BackConfigButton.setOnClickListener {
            toggleSlide(ConfigPage, "advanced")
        }
        BackConfigButton2.setOnClickListener {
            toggleSlide(ConfigPage, "advanced")
        }
        BackLogsButton.setOnClickListener {
            toggleSlide(ConfigPage, "advanced")
        }
        BackToLogsButton.setOnClickListener {
            toggleSlide(LogPage, "logs")
        }
        BackIdentityDetailsButton.setOnClickListener {
            toggleSlide(IdentityDetailsPage, "identities")
        }
        BackToLogsButton2.setOnClickListener {
            toggleSlide(LogPage, "logs")
        }
        BackLogsButton.setOnClickListener {
            toggleSlide(LogsPage, "advanced")
        }
        CopyLogButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Logs", LogDetails.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,"Log has been copied to your clipboard",Toast.LENGTH_LONG).show()
        }

        // Advanced Buttons
        TunnelButton.setOnClickListener {
            toggleSlide(ConfigPage, "config")
        }
        LogsButton.setOnClickListener {
            toggleSlide(LogsPage, "logs")
        }
        PacketLogsButton.setOnClickListener {
            LogTypeTitle.text = ("Packet Tunnel Logs")
            LogDetails.text = log_tunneler
            toggleSlide(LogPage, "logdetails")
        }

        // Dashboard Buttons
        //IdentityButton.setOnClickListener {
        //    toggleSlide(IdentityPage, "identities")
       // }
        //IdentityCount.setOnClickListener {
        //    toggleSlide(IdentityPage, "identities")
        //}

        LogDetails.movementMethod = ScrollingMovementMethod()
        ApplicationLogsButton.setOnClickListener {
            LogTypeTitle.text = ("Application Logs")
            LogDetails.text = log_application
            GlobalScope.launch(Dispatchers.IO) {
                val p = Runtime.getRuntime().exec("logcat -d -t 200 --pid=${Process.myPid()}")
                val lines = p.inputStream.bufferedReader().readText()

                Log.d("ziti", "log is ${lines.length} bytes")

                LogDetails.post {
                    LogDetails.text = lines
                }
            }
            toggleSlide(LogPage, "logdetails")
        }

        contextViewModel = ViewModelProvider(this).get(ZitiViewModel::class.java)
        contextViewModel.contexts().observe(this, { contextList ->
            //IdentityCards.removeAllViews()
            IdentityListing.removeAllViews()
            // create, remove cards
            var index = 0
            for (ctx in contextList) {
                val ctxModel = ViewModelProvider(this, ZitiContextModel.Factory(ctx)).get(ctx.name(), ZitiContextModel::class.java)
                val identityitem = IdentityItemView(this).apply { setModel(ctxModel) }
                ctxModel.name().observe(this, { n ->
                    IdIdentityDetailName.text = n
                })

                identityitem.setOnClickListener {
                    toggleSlide(IdentityDetailsPage, "identity")
                    IdDetailsEnrollment.text = ctxModel.status().value?.toString()
                    if (ctx.getStatus() == ZitiContext.Status.Active) {
                        IdOnOffSwitch.isChecked = true
                    }
                    IdOnOffSwitch.setOnCheckedChangeListener { _, state ->
                        ctx.setEnabled(state)
                    }
                    ctxModel.status().observe(this, { st ->
                        IdDetailsStatus.text = st.toString()
                    })
                    IdDetailsNetwork.text = ctx.controller()
                    IdDetailsNetwork.setOnClickListener {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Network", IdDetailsNetwork.text.toString())
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(applicationContext,IdDetailsNetwork.text.toString() + " has been copied to your clipboard",Toast.LENGTH_LONG).show()
                    }
                    var sCount = 0
                    ctxModel.services().observe(this, Observer { serviceList ->
                        IdDetailServicesList.removeAllViews()
                        for (service in serviceList) {
                            sCount++
                            val line = LineView(applicationContext)
                            line.label = service.name
                            line.value = service.interceptConfig?.toString() ?: ""
                            IdDetailServicesList.addView(line)
                        }
                    })
                    IdDetailForgetButton.setOnClickListener {

                        val builder = AlertDialog.Builder(this)
                        builder.setTitle("Confirm")
                        builder.setMessage("Are you sure you want to delete this identity from your device?")
                        builder.setIcon(android.R.drawable.ic_dialog_alert)

                        builder.setPositiveButton("Yes"){_, _ ->
                            ctxModel.delete()
                            Toast.makeText(applicationContext, ctx.name() + " removed", Toast.LENGTH_LONG).show()
                            toggleSlide(IdentityDetailsPage, "identities")
                        }

                        builder.setNeutralButton("Cancel"){_ , _ -> }

                        val alertDialog: AlertDialog = builder.create()
                        alertDialog.setCancelable(false)
                        alertDialog.show()
                    }
                }
                IdentityListing.addView(identityitem)
                index++
            }
            //IdentityCount.text = index.toString()
            if (index==0) {
                if (OffButton!=null) {
                    TurnOff()
                    //OffButton.getBackground().setAlpha(45)
                    OffButton.isClickable = false
                    StateButton.imageAlpha = 144
                }
            } else {
                if (OffButton!=null) {
                    //OffButton.getBackground().setAlpha(100)
                    OffButton.isClickable = true
                    StateButton.imageAlpha = 255
                }
            }
        })

        contextViewModel.stats().observe(this, {
            setSpeed(it.downRate, DownloadSpeed, DownloadMbps)
            setSpeed(it.upRate, UploadSpeed, UploadMbps)
        })

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
        // Ziti.resume()
        bindService(Intent(applicationContext, ZitiVPNService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

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

        speed.text = String.format("%.1f", r)
        label.text = l
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            10169 -> {
                if (resultCode == RESULT_OK)
                    startService(Intent(this, ZitiVPNService::class.java).setAction("start"))
            }
            10168 -> {
                if (resultCode == RESULT_OK)
                    startService(Intent(this, ZitiVPNService::class.java).setAction("stop"))
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

}
