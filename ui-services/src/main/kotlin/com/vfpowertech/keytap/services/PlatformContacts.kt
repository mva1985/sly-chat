package com.vfpowertech.keytap.services

import com.vfpowertech.keytap.core.PlatformContact
import nl.komponents.kovenant.Promise

/** Interface for accessing a platform's contact data. */
interface PlatformContacts {
    fun fetchContacts(): Promise<List<PlatformContact>, Exception>
}