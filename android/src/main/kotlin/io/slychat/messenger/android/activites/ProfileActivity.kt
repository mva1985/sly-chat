package io.slychat.messenger.android.activites

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import io.slychat.messenger.android.AndroidApp
import io.slychat.messenger.android.R
import org.slf4j.LoggerFactory

class ProfileActivity : BaseActivity() {
    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var app : AndroidApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log.debug("onCreate")

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_profile)

        app = AndroidApp.get(this)

        val actionBar = findViewById(R.id.profile_toolbar) as Toolbar
        actionBar.title = resources.getString(R.string.profile_title)
        setSupportActionBar(actionBar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        createEventListeners()

        init()
    }

    private fun init() {
        displayInfo()
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            android.R.id.home -> { finish() }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun createEventListeners() {
        val updateButton = findViewById(R.id.profile_update_btn) as Button
        updateButton.setOnClickListener {
            startUpdateProcess()
        }
    }

    private fun startUpdateProcess() {
        startActivity(Intent(baseContext, UpdateProfileActivity::class.java))
    }

    private fun displayInfo() {
        val emailVal = findViewById(R.id.profile_email_value) as TextView
        val nameVal = findViewById(R.id.profile_name_value) as TextView
        val deviceVal = findViewById(R.id.profile_device_id) as TextView
        val phoneVal = findViewById(R.id.profile_phone_value) as TextView
        val publicKeyVal = findViewById(R.id.profile_public_key_value) as TextView

        emailVal.text = app.accountInfo?.email
        nameVal.text = app.accountInfo?.name
        deviceVal.text = app.accountInfo?.deviceId.toString()
        publicKeyVal.text = app.publicKey
        phoneVal.text = app.accountInfo?.phoneNumber
    }

    private fun setAppActivity() {
        log.debug("set ui visible")
        app.setCurrentActivity(this, true)
    }

    private fun clearAppActivity() {
        log.debug("set ui hidden")
        app.setCurrentActivity(this, false)
    }

    override fun onStart() {
        super.onStart()
        log.debug("onStart")
    }

    override fun onPause() {
        super.onPause()
        clearAppActivity()
        log.debug("onPause")
    }

    override fun onResume() {
        super.onResume()
        setAppActivity()
        init()
        log.debug("onResume")
    }

    override fun onStop() {
        super.onStop()
        log.debug("onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        clearAppActivity()
        log.debug("onDestroy")
    }
}