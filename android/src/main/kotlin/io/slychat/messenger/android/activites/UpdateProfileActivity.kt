package io.slychat.messenger.android.activites

import android.content.DialogInterface
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import io.slychat.messenger.android.AndroidApp
import io.slychat.messenger.android.R
import io.slychat.messenger.android.activites.services.impl.AccountServiceImpl
import io.slychat.messenger.core.persistence.AccountInfo
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi
import org.slf4j.LoggerFactory
import java.util.*

class UpdateProfileActivity: AppCompatActivity() {
    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var accountService : AccountServiceImpl

    private val phoneUtil = PhoneNumberUtil.getInstance()

    private lateinit var app : AndroidApp

    private lateinit var accountInfo : AccountInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log.debug("onCreate")

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_update_profile)

        val actionBar = findViewById(R.id.update_profile_toolbar) as Toolbar
        actionBar.title = "Update Profile"
        setSupportActionBar(actionBar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        app = AndroidApp.get(this)

        accountService = AccountServiceImpl(this)
        setEventListener()
    }

    private fun init() {
        accountInfo = accountService.getAccountInfo()
        displayInfo()
        populateCountrySelect()
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            android.R.id.home -> { finish() }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setEventListener() {
        val updatePhoneBtn = findViewById(R.id.update_profile_phone_button) as Button
        val updateInfoBtn = findViewById(R.id.update_profile_info_button) as Button

        updateInfoBtn.setOnClickListener {
            updateProfileInfo()
        }

        updatePhoneBtn.setOnClickListener {
            log.debug("click listener")
            handlePhoneUpdate()
        }
    }

    private fun updateProfileInfo() {
        val mName = findViewById(R.id.update_profile_name_input) as EditText
        val mEmail = findViewById(R.id.update_profile_email_input) as EditText
    }

    private fun handlePhoneUpdate() {
        val mPhone = findViewById(R.id.update_profile_phone_input) as EditText
        val mCountry = findViewById(R.id.update_profile_country_spinner) as Spinner
        val country = mCountry.selectedItem as String
        val phoneInput = mPhone.text.toString()
        val parsed : Phonenumber.PhoneNumber
        val currentPhone = accountInfo.phoneNumber

        if(phoneInput.isEmpty()) {
            mPhone.error = "Phone number is required"
            return
        }

        try {
            parsed = phoneUtil.parse(phoneInput, country)
        } catch (e: Exception) {
            mPhone.error = "Phone does not seem to be valid"
            return
        }

        if (!phoneUtil.isValidNumberForRegion(parsed, country)) {
            mPhone.error = "Phone does not seem to be valid"
            return
        }

        val phone = phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        val formattedPhone = phone.replace("+", "")

        if (formattedPhone == currentPhone) {
            log.debug("Phone is the same as the currently used one")
            return
        }

        checkPhoneAvailability(formattedPhone)
    }

    private fun updatePhone(phone: String) {
        log.debug("In update phone")
        accountService.updatePhone(phone) successUi { result ->
            if (result.successful) {
                openSmsVerificationModal(null)
            }
            else {
                log.debug(result.errorMessage)
            }
        }
    }

    private fun openSmsVerificationModal(error: String?) {
        val builder = AlertDialog.Builder(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_sms_verification, null)
        val mSmsCode = view.findViewById(R.id.sms_verification_code_field) as EditText

        if (error !== null)
            mSmsCode.error = error

        builder.setMessage("You should receive a sms verification code")
                .setTitle("Sms Verification")
                .setView(view)
                .setCancelable(false)
                .setPositiveButton("Submit", DialogInterface.OnClickListener { dialogInterface, i ->
                    val code = mSmsCode.text.toString()
                    accountService.verifyPhone(code) successUi { result ->
                        if (result.successful && result.accountInfo !== null) {
                            updateAccountInfo(result.accountInfo)
                        }
                        else {
                            openSmsVerificationModal(result.errorMessage)
                        }
                    }

                })
                .setNegativeButton("Cancel", DialogInterface.OnClickListener { dialogInterface, i ->
                })
        val dialog = builder.create()
        dialog.show()
    }

    private fun updateAccountInfo(newAccountInfo: AccountInfo) {
        accountService.updateAccountInfo(newAccountInfo) successUi {
            finish()
        } failUi {
            log.error("Failed to update the account info")
        }
    }

    private fun checkPhoneAvailability(phone: String) {
        val mPhone = findViewById(R.id.update_profile_phone_input) as EditText

        accountService.checkPhoneNumberAvailability(phone) successUi { available ->
            if(available) {
                updatePhone(phone)
            }
            else {
                mPhone.error = "Phone number is already in use"
            }
        } failUi {
            mPhone.error = "An error occurred"
        }
    }

    private fun populateCountrySelect() {
        val countryList = phoneUtil.supportedRegions.sortedBy { it }.toMutableList()
        val position = countryList.indexOf(Locale.getDefault().country)
        val mCountry = findViewById(R.id.update_profile_country_spinner) as Spinner
        val countryAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, countryList)
        mCountry.adapter = countryAdapter
        mCountry.setSelection(position)
    }

    private fun displayInfo() {
        val phone = accountInfo.phoneNumber
        val name = accountInfo.name
        val email = accountInfo.email

        setPhoneValue(phone)
        setProfileInfo(name, email)
    }

    private fun setPhoneValue(phone: String) {
        val mPhone = findViewById(R.id.update_profile_phone_input) as EditText
        mPhone.text.clear()
        mPhone.append(phone)
    }

    private fun setProfileInfo(name: String, email: String) {
        val mName = findViewById(R.id.update_profile_name_input) as EditText
        val mEmail = findViewById(R.id.update_profile_email_input) as EditText

        mEmail.text.clear()
        mEmail.append(email)

        mName.text.clear()
        mName.append(name)
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