package io.slychat.messenger.android.activites

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.support.design.widget.NavigationView
import android.support.v4.view.GravityCompat
import android.support.v4.widget.DrawerLayout
import android.support.v7.app.AlertDialog
import android.support.v7.widget.Toolbar
import android.view.WindowManager
import android.view.Menu
import android.view.MenuItem
import android.view.Gravity
import android.view.View
import android.view.LayoutInflater
import android.widget.*
import hani.momanii.supernova_emoji_library.Actions.EmojIconActions
import hani.momanii.supernova_emoji_library.Helper.EmojiconEditText
import io.slychat.messenger.android.AndroidApp
import io.slychat.messenger.android.R
import io.slychat.messenger.android.activites.services.impl.ContactServiceImpl
import io.slychat.messenger.android.activites.services.impl.GroupServiceImpl
import io.slychat.messenger.android.activites.services.impl.MessengerServiceImpl
import io.slychat.messenger.android.activites.services.impl.SettingsServiceImpl
import io.slychat.messenger.android.formatTimeStamp
import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.persistence.*
import io.slychat.messenger.services.MessageUpdateEvent
import io.slychat.messenger.services.PageType
import io.slychat.messenger.services.contacts.ContactEvent
import io.slychat.messenger.services.messaging.ConversationMessage
import io.slychat.messenger.services.messaging.GroupEvent
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi
import org.slf4j.LoggerFactory
import android.view.animation.AnimationUtils



class ChatActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var app: AndroidApp
    private lateinit var messengerService: MessengerServiceImpl
    private lateinit var contactService: ContactServiceImpl
    private lateinit var groupService: GroupServiceImpl
    private lateinit var settingsService: SettingsServiceImpl

    private lateinit var contactInfo: ContactInfo
    private lateinit var groupInfo: GroupInfo

    private var groupMembers: Map<UserId, ContactInfo>? = null

    private lateinit var conversationId: ConversationId
    private var chatDataLink: MutableMap<String, Int> = mutableMapOf()

    private var expireToggled = false
    private var expireDelay: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log.debug("onCreate")

        val layoutId: Int
        val isGroup = intent.getBooleanExtra("EXTRA_ISGROUP", false)
        if(isGroup) {
            val gIdString = intent.getStringExtra("EXTRA_ID")
            if(gIdString == null)
                finish()
            conversationId = GroupId(gIdString).toConversationId()
            layoutId = R.layout.activity_group_chat
        }
        else {
            val uIdLong = intent.getLongExtra("EXTRA_ID", -1L)
            if(uIdLong == -1L)
                finish()
            conversationId = UserId(uIdLong).toConversationId()
            layoutId = R.layout.activity_user_chat
        }

        app = AndroidApp.get(this)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(layoutId)

        val actionBar = findViewById(R.id.chat_toolbar) as Toolbar
        actionBar.title = ""
        setSupportActionBar(actionBar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setNavigationMenu()

        setupEmojicon()

        messengerService = MessengerServiceImpl(this)
        contactService = ContactServiceImpl(this)
        groupService = GroupServiceImpl(this)
        settingsService = SettingsServiceImpl(this)

        getDisplayInfo()
        createEventListeners()
    }

    private fun setupEmojicon() {
        val icons: String
        val tabs: String
        val bg: String
        val keyboard: Int
        val smiley: Int
        val currentTheme = app.appComponent.appConfigService.appearanceTheme

        if (currentTheme.isNullOrEmpty() || currentTheme == SettingsServiceImpl.darkTheme) {
            icons = "#FFFFFF"
            tabs = "#222222"
            bg = "#222222"
            smiley = R.drawable.ic_tag_faces
            keyboard = R.drawable.ic_keyboard
        }
        else {
            icons = "#222222"
            tabs = "#FFFFFF"
            bg = "#FFFFFF"
            smiley = R.drawable.ic_tag_faces_black
            keyboard = R.drawable.ic_keyboard_black
        }

        val chatInput = findViewById(R.id.chat_input) as EditText
        val rootView = findViewById(R.id.chat_root_view)
        val emojiButton = findViewById(R.id.chat_emoji_button) as ImageButton
        val emojiInput = chatInput as EmojiconEditText

        val emojIcon = EmojIconActions(this, rootView, emojiInput, emojiButton, icons, tabs, bg)
        emojIcon.ShowEmojIcon()
        emojIcon.setIconsIds(keyboard, smiley)
    }

    private fun setNavigationMenu() {
        val navigationView: NavigationView
        if(conversationId is ConversationId.User) {
            navigationView = findViewById(R.id.chat_user_nav_view) as NavigationView
        }
        else {
            navigationView = findViewById(R.id.chat_group_nav_view) as NavigationView
        }

        navigationView.setNavigationItemSelectedListener(this)

        val drawerName = navigationView.getHeaderView(0).findViewById(R.id.drawer_user_name) as TextView
        val drawerEmail = navigationView.getHeaderView(0).findViewById(R.id.drawer_user_email) as TextView

        drawerEmail.text = app.accountInfo?.email
        drawerName.text = app.accountInfo?.name
    }

    fun getDisplayInfo() {
        val cId = conversationId
        if(cId is ConversationId.User) {
            contactService.getContact(cId.id) successUi {
                if (it == null)
                    finish()
                else {
                    contactInfo = it
                    val actionBar = findViewById(R.id.chat_toolbar) as Toolbar
                    actionBar.title = it.name
                }
            } failUi {
                log.error("Could not find contact to load chat page.")
                finish()
            }
        }
        else if(cId is ConversationId.Group){
            groupService.getGroupInfo(cId.id) successUi {
                if(it != null) {
                    groupInfo = it
                    val actionBar = findViewById(R.id.chat_toolbar) as Toolbar
                    actionBar.title = it.name
                }
                else
                    finish()
            } failUi {
                log.error("Could not find the group to load chat page")
                finish()
            }
        }
    }

    private fun createEventListeners() {
        val submitBtn = findViewById(R.id.submit_chat_btn) as ImageButton
        val expireBtn = findViewById(R.id.expire_chat_btn) as ImageButton
        val expireSlider = findViewById(R.id.expiration_slider) as SeekBar

        submitBtn.setOnClickListener {
            handleNewMessageSubmit()
        }

        expireBtn.setOnClickListener {
            handleExpireMessageToggle()
        }

        expireSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                setExpireDelay(seekBar.progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                setExpireDelay(seekBar.progress.toLong())
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setExpireDelay(progress.toLong())
            }
        })
    }

    private fun init() {
        val cId = conversationId
        if(cId is ConversationId.User) {
            app.dispatchEvent("PageChange", PageType.CONVO, cId.id.toString())
        }
        else if(cId is ConversationId.Group){
            app.dispatchEvent("PageChange", PageType.GROUP, cId.id.string)
        }
        setAppActivity()
        setListeners()
        messengerService.fetchMessageFor(conversationId, 0, 100) successUi { messages ->
            if (cId is ConversationId.Group) {
                groupService.getMembersInfo(cId.id) successUi { members ->
                    groupMembers = members
                    displayMessages(messages)
                }
            }
            else
                displayMessages(messages)
        }
    }

    private fun handleExpireMessageToggle() {
        if (expireDelay == null)
            expireDelay = settingsService.getLastMessageTtl()

        if (expireToggled)
            hideExpirationSlider()
        else
            showExpirationSlider()
    }

    private fun showExpirationSlider() {
        val expireSlider = findViewById(R.id.expiration_slider) as SeekBar
        val expirationSliderContainer = findViewById(R.id.expiration_slider_container)

        var delay = expireDelay
        if (delay == null)
            delay = 5000

        expireSlider.progress = delay.toInt() / 1000
        setExpireDelay(delay / 1000)

        expirationSliderContainer.visibility = View.VISIBLE
        expireToggled = true
    }

    private fun setExpireDelay(delay: Long) {
        expireDelay = delay * 1000

        val expirationDelay = findViewById(R.id.expiration_delay) as TextView
        expirationDelay.text = delay.toString()
    }

    private fun hideExpirationSlider() {
        val expirationSliderContainer = findViewById(R.id.expiration_slider_container)
        expirationSliderContainer.visibility = View.GONE
        expireToggled = false
    }

    private fun displayMessages(messages: List<ConversationMessageInfo>) {
        val chatList = findViewById(R.id.chat_list) as LinearLayout
        chatList.removeAllViews()
        messages.reversed().forEach { message ->
            chatList.addView(createMessageNode(message))
            scrollToBottom()
        }
    }

    private fun createMessageNode(messageInfo: ConversationMessageInfo): View {
        val layout : Int
        if(messageInfo.info.isSent)
            layout = R.layout.sent_message_node
        else
            layout = R.layout.received_message_node

        val chatList = findViewById(R.id.chat_list) as LinearLayout
        val messageNode = LayoutInflater.from(this).inflate(layout, chatList, false)
        val message = messageNode.findViewById(R.id.message) as TextView
        val timespan = messageNode.findViewById(R.id.timespan) as TextView

        val nodeId = View.generateViewId()
        chatDataLink.put(messageInfo.info.id, nodeId)
        messageNode.id = nodeId

        val messageLayout = messageNode.findViewById(R.id.message_node_layout) as LinearLayout

        if (messageInfo.info.isExpired) {
            message.text = resources.getString(R.string.chat_expired_message_text)
            timespan.visibility = View.GONE
            messageLayout.visibility = View.VISIBLE
            return messageNode
        }

        val speaker = messageInfo.speaker
        val members = groupMembers
        if(speaker !== null && conversationId is ConversationId.Group && members !== null) {
            val contact = members[speaker]
            if (contact !== null) {
                val speakerName = messageNode.findViewById(R.id.chat_group_speaker_name) as TextView
                speakerName.visibility = View.VISIBLE
                speakerName.text = contact.name
            }
        }

        val time: String

        if(messageInfo.info.receivedTimestamp == 0L)
            time = resources.getString(R.string.chat_delivering_time_string)
        else
            time = formatTimeStamp(messageInfo.info.receivedTimestamp)

        timespan.text = time
        message.text = messageInfo.info.message

        if (messageInfo.info.ttlMs > 0 && !messageInfo.info.isSent && messageInfo.info.expiresAt <= 0) {
            val expirationLayout = messageNode.findViewById(R.id.expiring_message_layout) as LinearLayout
            expirationLayout.visibility = View.VISIBLE
            messageNode.setOnClickListener {
                showExpiringMessage(it, messageInfo)
            }

            val pulse = AnimationUtils.loadAnimation(this, R.anim.pulse)
            messageNode.startAnimation(pulse)
        }
        else {
            messageLayout.visibility = View.VISIBLE
        }

        return messageNode
    }

    private fun showExpiringMessage(node: View, messageInfo: ConversationMessageInfo) {
        val messageLayout = node.findViewById(R.id.message_node_layout)
        val expireLayout = node.findViewById(R.id.expiring_message_layout)

        expireLayout.visibility = View.GONE
        messageLayout.visibility = View.VISIBLE

        node.clearAnimation()

        messengerService.startMessageExpiration(conversationId, messageInfo.info.id) failUi {
            log.error("Could not start message expiration for message id: ${messageInfo.info.id}")
        }
    }

    private fun handleNewMessageSubmit() {
        var ttl = 0L
        val chatInput = findViewById(R.id.chat_input) as EditText
        val messageValue = chatInput.text.toString()
        if(messageValue.isEmpty())
            return

        val delay = expireDelay
        if (expireToggled && delay != null) {
            ttl = delay
            settingsService.setLastMessageTtl(ttl)
        }

        messengerService.sendMessageTo(conversationId, messageValue, ttl) successUi {
            chatInput.setText("")
        } failUi {
            log.error("Send message failed", it.stackTrace)
        }
    }

    private fun scrollToBottom() {
        val chatScrollView = findViewById(R.id.chat_list_scrollview) as ScrollView
        chatScrollView.post {
            chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun onNewMessage(newMessageInfo: ConversationMessage) {
        val cId = newMessageInfo.conversationId
        if (cId == conversationId) {
            handleNewMessageDisplay(newMessageInfo)
        }
    }

    private fun onContactEvent(event: ContactEvent) {
        if(event is ContactEvent.Blocked || event is ContactEvent.Removed) {
            finish()
        }
    }

    private fun onGroupEvent(event: GroupEvent) {
        if (event is GroupEvent.Blocked || event is GroupEvent.Parted) {
            finish()
        }
    }

    private fun onMessageUpdate(event: MessageUpdateEvent) {
        when(event) {
            is MessageUpdateEvent.Delivered -> { handleDeliveredMessageEvent(event) }
            is MessageUpdateEvent.Expired -> { handleExpiredMessage(event) }
            is MessageUpdateEvent.Deleted -> { handleDeletedMessage(event) }
            is MessageUpdateEvent.DeletedAll -> { handleDeletedAllMessage(event) }
            is MessageUpdateEvent.DeliveryFailed -> { handleFailedDelivery(event) }
            is MessageUpdateEvent.Expiring -> { log.debug("Expiring") }
        }
    }

    private fun handleDeliveredMessageEvent(event: MessageUpdateEvent.Delivered) {
        val cId = event.conversationId
        if(cId == conversationId) {
            updateMessageDelivered(event)
        }
    }

    private fun handleFailedDelivery(event: MessageUpdateEvent.DeliveryFailed) {
        val nodeId = chatDataLink[event.messageId]
        if(nodeId === null) {
            log.debug("Failed message update, Message id: ${event.messageId} does not exist in the current chat page")
            return
        }

        val node = findViewById(nodeId)
        (node.findViewById(R.id.timespan) as TextView).text = resources.getString(R.string.chat_failed_message_delivery)
    }

    private fun handleDeletedAllMessage(event: MessageUpdateEvent.DeletedAll) {
        val cId = event.conversationId
        if(cId == conversationId) {
            val chatList = findViewById(R.id.chat_list) as LinearLayout
            chatList.removeAllViews()
        }
    }

    private fun handleDeletedMessage(event: MessageUpdateEvent.Deleted) {
        val cId = event.conversationId
        if(cId == conversationId) {
            event.messageIds.forEach {
                val nodeId = chatDataLink[it]
                if(nodeId === null) {
                    log.debug("Message Deleted event, Message id: $it does not exist in the current chat page")
                } else {
                    val chatList = findViewById(R.id.chat_list) as LinearLayout
                    chatList.removeView(findViewById(nodeId))
                }
            }
        }
    }

    private fun handleExpiredMessage(event: MessageUpdateEvent.Expired) {
        val nodeId = chatDataLink[event.messageId]
        if (nodeId === null) {
            log.debug("Message Expired event, Message id: ${event.messageId} does not exist in the current chat page")
            return
        }

        val messageNode = findViewById(nodeId) as LinearLayout
        val message = messageNode.findViewById(R.id.message) as TextView
        val timespan = messageNode.findViewById(R.id.timespan) as TextView
        val speakerName = messageNode.findViewById(R.id.chat_group_speaker_name) as TextView
        speakerName.visibility = View.GONE
        message.text = resources.getString(R.string.chat_expired_message_text)
        timespan.text = ""
        timespan.visibility = View.GONE
    }

    private fun updateMessageDelivered(event: MessageUpdateEvent.Delivered) {
        val nodeId = chatDataLink[event.messageId]
        if (nodeId === null) {
            log.debug("Message Delivered update, Message id: ${event.messageId} does not exist in the current chat page")
            return
        }

        val node = findViewById(nodeId)
        (node.findViewById(R.id.timespan) as TextView).text = formatTimeStamp(event.deliveredTimestamp)
    }

    private fun handleNewMessageDisplay(newMessage: ConversationMessage) {
        val chatList = findViewById(R.id.chat_list) as LinearLayout
        chatList.addView(createMessageNode(newMessage.conversationMessageInfo))

        scrollToBottom()
    }

    private fun setListeners() {
        messengerService.addNewMessageListener({ onNewMessage(it) })
        messengerService.addMessageUpdateListener({ onMessageUpdate(it) })
        contactService.addContactListener { onContactEvent(it) }
        groupService.addGroupListener { onGroupEvent(it) }
    }

    private fun clearListners() {
        messengerService.clearListeners()
        contactService.clearListeners()
        groupService.clearListeners()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.layout_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_menu -> {
                val drawer = findViewById(R.id.chat_drawer_layout) as DrawerLayout
                drawer.openDrawer(Gravity.END)
            }
            android.R.id.home -> { finish() }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadContactInfo() {
        val cId = conversationId
        if(cId is ConversationId.User) {
            val intent = Intent(baseContext, ContactInfoActivity::class.java)
            intent.putExtra("EXTRA_USERID", cId.id.long)
            intent.putExtra("EXTRA_USER_NAME", contactInfo.name)
            intent.putExtra("EXTRA_USER_EMAIL", contactInfo.email)
            intent.putExtra("EXTRA_USER_PUBKEY", contactInfo.publicKey)
            startActivity(intent)
        }
    }

    private fun blockContact() {
        val cId = conversationId
        if(cId is ConversationId.User) {
            contactService.blockContact(cId.id) failUi {
                log.error("Failed to block user id : ${cId.id}")
            }
        }
    }

    private fun deleteConversation() {
        messengerService.deleteConversation(conversationId) failUi {
            log.error("Failed to delete the conversation")
        }
    }

    private fun deleteContact() {
        contactService.deleteContact(contactInfo) successUi {
            if (!it)
                log.info("Failed to delete user id : ${contactInfo.email}")
        } failUi {
            log.error("Failed to delete user id : ${contactInfo.email}")
        }
    }

    private fun deleteGroup() {
        val cId = conversationId
        if(cId is ConversationId.Group) {
            groupService.deleteGroup(cId.id) successUi {
                finish()
            } failUi {
                log.error("Failed to delete the group")
            }
        }
    }

    private fun blockGroup() {
        val cId = conversationId
        if(cId is ConversationId.Group) {
            groupService.blockGroup(cId.id) failUi {
                log.error("Failed to block the group")
            }
        }
    }

    private fun loadGroupInfo() {
        val cId = conversationId
        if(cId is ConversationId.Group) {
            log.debug("loading group info")
        }
    }

    private fun openConfirmationDialog(title: String, message: String, callBack: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, DialogInterface.OnClickListener { dialog: DialogInterface, whichButton: Int ->
                callBack()
        }).setNegativeButton(android.R.string.no, null).show()
    }

    override fun onBackPressed() {
        val drawer = findViewById(R.id.chat_drawer_layout) as DrawerLayout
        if(drawer.isDrawerOpen(GravityCompat.END)) {
            drawer.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressed()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.menu_block_contact -> { openConfirmationDialog(resources.getString(R.string.chat_block_contact_title), resources.getString(R.string.chat_block_contact_text), { blockContact() }) }
            R.id.menu_delete_contact -> { openConfirmationDialog(resources.getString(R.string.chat_delete_contact_title), resources.getString(R.string.chat_delete_contact_text), { deleteContact() }) }
            R.id.menu_delete_conversation -> { openConfirmationDialog(resources.getString(R.string.chat_delete_conversation_title), resources.getString(R.string.chat_delete_conversation_text), { deleteConversation() }) }
            R.id.menu_contact_info -> { loadContactInfo() }
            R.id.menu_group_info -> { loadGroupInfo() }
            R.id.menu_delete_group -> { openConfirmationDialog(resources.getString(R.string.chat_delete_group_title), resources.getString(R.string.chat_delete_group_text), { deleteGroup() })}
            R.id.menu_block_group -> { openConfirmationDialog(resources.getString(R.string.chat_block_group_title), resources.getString(R.string.chat_block_group_text), { blockGroup() })}
        }

        val drawer = findViewById(R.id.chat_drawer_layout) as DrawerLayout
        drawer.closeDrawer(GravityCompat.END)
        return true
    }

    fun setAppActivity() {
        app.setCurrentActivity(this, true)
    }

    fun clearAppActivity() {
        app.setCurrentActivity(this, false)
    }

    override fun onStart() {
        super.onStart()
        log.debug("onStart")
    }

    override fun onPause() {
        super.onPause()
        log.debug("onPause")
        clearAppActivity()
        clearListners()
    }

    override fun onResume() {
        super.onResume()
        init()
        log.debug("onResume")
    }

    override fun onStop() {
        super.onStop()
        log.debug("onStop")
        clearListners()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearAppActivity()
        log.debug("onDestroy")
    }
}