var ConnectionController = function () {
    this.relayConnected = true;
    this.networkAvailable = true;
    this.notification = null;
    this.allowRelayNotificationPage = [
        "contacts",
        "chat",
        "addContact",
        "profile",
        'createGroup',
        'contactInfo',
        'settings',
        'blockedContacts'
    ];
};

ConnectionController.prototype = {
    init : function () {
        networkStatusService.addRelayStatusChangeListener(function (status) {
            this.relayConnected = status.online;
            this.handleConnectionDisplay();
        }.bind(this));

        networkStatusService.addNetworkStatusChangeListener(function (status) {
            this.networkAvailable = status.online;
            this.handleConnectionDisplay();
        }.bind(this));
    },

    handleConnectionDisplay : function () {
        if (this.networkAvailable == true && this.relayConnected == true) {
            if (this.notification !== null) {
                slychat.closeNotification(this.notification);
                this.notification = null;
            }
        }
        else {
            setTimeout(function () {
                var currentPage = $$('#mainView').data('page');
                if (this.networkAvailable !== true) {
                    this.openConnectionNotification("No network access");
                }
                else if (this.relayConnected !== true) {
                    if (this.notification === null) {
                        if ($.inArray(currentPage, this.allowRelayNotificationPage) > -1)
                            this.openConnectionNotification("Attempting to reconnect");
                    }
                    else {
                        if ($.inArray(currentPage, this.allowRelayNotificationPage) > -1)
                            this.openConnectionNotification("Attempting to reconnect");
                        else
                            slychat.closeNotification(this.notification);
                    }
                }
            }.bind(this), 5000);
        }
    },

    openConnectionNotification : function (message) {
        if (this.notification === null) {
            var options = {
                title: message,
                additionalClass: 'connection-notification',
                closeOnClick: false
            };

            this.notification = slychat.addNotification(options);
        }
        else {
            $(this.notification).removeClass("no-network not-connected");
            $(this.notification).find(".item-text").html(message);
        }
    },

    clearCache : function () {
        this.relayConnected = true;
        this.networkAvailable = true;
        this.notification = null;
    }
};