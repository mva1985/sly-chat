var SettingsController = function () {
    this.notificationConfig = null;
};

SettingsController.ids = {
    notificationsEnabled : '#notifications-enabled-checkbox',
    notificationsSound : '#notification-sound-select-btn'
};

//TODO prevent user from editing until we've received the initial config
SettingsController.prototype = {
    init : function () {
        this.addConfigListeners();
    },

    onPageInit : function () {
        settingsController.initEventHandlers();
        settingsController.displaySettings();
    },

    addConfigListeners : function () {
        configService.addNotificationConfigChangeListener(function (newConfig) {
            this.notificationConfig = newConfig;
            this.refreshNotificationConfig();
        }.bind(this));
    },

    refreshNotificationConfig : function () {
        var c = this.notificationConfig;

        $(SettingsController.ids.notificationsEnabled).prop('checked', c.enabled);
    },

    updateNotificationConfig : function () {
        configService.setNotificationConfig(this.notificationConfig).catch(function (e) {
            exceptionController.handleError(e);
        });
    },

    setNotificationsEnabled : function (isEnabled) {
        this.notificationConfig.enabled = isEnabled;

        this.updateNotificationConfig();
    },

    setNotificationSound : function (sound) {
        this.notificationConfig.sound = sound;

        this.updateNotificationConfig();
    },

    selectNotificationSound : function () {
        windowService.selectNotificationSound(this.notificationConfig.sound).then(function (result) {
            if(result.ok)
                this.setNotificationSound(result.value);
        }.bind(this)).catch(function (e) {
            exceptionController.handleError(e);
        });
    },

    displaySettings : function () {
        this.refreshNotificationConfig();
    },

    initEventHandlers : function () {
        $(SettingsController.ids.notificationsEnabled).on('change', function (e) {
            e.preventDefault();

            settingsController.setNotificationsEnabled(e.target.checked);
        });

        $(SettingsController.ids.notificationsSound).on('click', function (e) {
            settingsController.selectNotificationSound();
        });
    }
};