package com.vfpowertech.keytap.core.persistence.json

import com.vfpowertech.keytap.core.persistence.InstallationData
import com.vfpowertech.keytap.core.persistence.InstallationDataPersistenceManager
import com.vfpowertech.keytap.core.persistence.json.readObjectFromJsonFile
import com.vfpowertech.keytap.core.persistence.json.writeObjectToJsonFile
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.task
import java.io.File

class JsonInstallationDataPersistenceManager(private val path: File) : InstallationDataPersistenceManager {
    override fun store(installationData: InstallationData): Promise<Unit, Exception> = task {
        writeObjectToJsonFile(path, installationData)
    }

    override fun retrieve(): Promise<InstallationData?, Exception> = task {
        readObjectFromJsonFile(path, InstallationData::class.java)
    }
}