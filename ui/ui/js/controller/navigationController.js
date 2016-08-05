var NavigationController = function () {};

NavigationController.prototype = {
    init : function () {
        window.navigationService = {
            goBack: function () {
                this.goBack();
            }.bind(this),

            goTo: function(url) {
                this.goTo(url);
            }.bind(this)
        };
    },

    goTo : function (page) {
        if(page == "contacts") {
            historyService.clear().then(function () {
                this.loadPage("contacts.html", false);
            });
        }
        else if(/^user\//.test(page)) {
            var id = page.split("/", 2)[1];

            //so we clear the history and set it to contacts > chat
            historyService.replace(["contacts.html"]).then(function () {
                if($$('.popup.modal-in').length > 0) {
                    $$('.popup.modal-in').find('.close-popup-btn').trigger('click');
                }
                else if ($$('.picker-modal.modal-in').length > 0) {
                    slychat.closeModal();
                }
                var contact = contactController.getContact(id);
                if (contact !== false)
                    contactController.loadChatPage(contact, false);
                else
                    contactController.fetchAndLoadChat(id);
            });
        }
        else if (/^group\//.test(page)) {
            var groupId = page.split("/", 2)[1];

            historyService.replace(['contacts.html']).then(function () {
                if($$('.popup.modal-in').length > 0) {
                    $$('.popup.modal-in').find('.close-popup-btn').trigger('click');
                }
                else if ($$('.picker-modal.modal-in').length > 0) {
                    slychat.closeModal();
                }
                var group = groupController.getGroup(groupId);
                if (group !== false)
                    contactController.loadChatPage(group, false, true);
                else
                    groupController.fetchAndLoadGroupChat(groupId);
            });
        }
        else {
            console.error("Unknown page: " + page);
        }

        this.hideSplashScreen();
    },

    goBack : function () {
        if($$('.popup.modal-in').length > 0) {
            $$('.popup.modal-in').find('.close-popup-btn').trigger('click');
        }
        else if ($$('.picker-modal.modal-in').length > 0) {
            slychat.closeModal();
        }
        else if ($$(".actions-modal.modal-in").length > 0 ) {
            slychat.closeModal();
        }
        else {
            historyService.pop().then(function (url) {
                if (url == "") {
                    windowService.minimize();
                }
                else {
                    this.load(url);
                }
            }.bind(this)).catch(function (e) {
                exceptionController.handleError(e);
            });
        }
    },

    loadPage : function (url, pushCurrentPage, options) {
        if (pushCurrentPage === undefined)
            pushCurrentPage = true;

        if (pushCurrentPage === true)
            this.pushHistory();

        this.load(url, options)
    },

    pushHistory : function () {
        var currentPage = this.getCurrentPage();

        if (currentPage !== "index.html") {
            historyService.push(currentPage).catch(function (e) {
                exceptionController.handleError(e);
            });
        }
    },

    clearHistory : function () {
        historyService.clear().catch(function (e){
            exceptionController.handleError(e);
        });
    },

    loadMessageLink : function (url) {
        platformService.openURL(url).catch(function (e) {
            exceptionController.handleError(e);
        });
    },

    load : function (url, options) {
        var currentContatctId = null;
        if(url === "chat.html" && options !== undefined && options.query !== undefined && options.query.id !== undefined)
            currentContatctId = options.query.id;

        var currentState = {
            "currentPage" : url,
            "currentContact" : currentContatctId
        };

        stateService.setState(currentState).catch(function (e) {
            exceptionController.handleError(e);
        });

        var page;
        var extra = "";

        if(/contacts.html$/.test(url)) {
            page = "CONTACTS"
        }
        else if(/chat.html$/.test(url)) {
            if(options.group === true)
                page = "GROUP";
            else
                page = "CONVO";
            extra = currentContatctId;
        }

        if(page !== undefined) {
            eventService.dispatchEvent({
                "eventType": "PageChange",
                "page": page,
                "extra": extra
            });
        }

        if (options === undefined) {
            options = {
                url: url
            }
        }

        mainView.router.load(options);
    },

    getCurrentPage : function () {
        var pages = {
            contacts: 'contacts.html',
            chat: 'chat.html',
            addContact: 'addContact.html',
            index: 'index.html',
            login: 'login.html',
            register: 'register.html',
            smsVerification: 'smsVerification.html',
            updatePhone: 'updatePhone.html',
            profile: 'profile.html',
            createGroup: 'createGroup.html'
        };
        var page = $$('#mainView').data('page');

        return pages[page];
    },

    hideSplashScreen : function () {
        if (firstLoad == true) {
            window.loadService.loadComplete();
            firstLoad = false;
        }
    },

    openMenu : function () {
        var buttons = [
            {
                text: 'Profile',
                onClick: function () {
                    navigationController.loadPage('profile.html', true);
                }
            },
            {
                text: 'Logout',
                onClick: function () {
                    loginController.logout();
                }
            },
            {
                text: 'Add Contact',
                onClick: function () {
                    navigationController.loadPage("addContact.html", true);
                }
            },
            {
                text: 'Create Group',
                onClick: function () {
                    navigationController.loadPage('createGroup.html', true);
                }
            },
            {
                text: 'Cancel',
                color: 'red',
                onClick: function () {
                }
            }
        ];
        slychat.actions(buttons);
    }

};