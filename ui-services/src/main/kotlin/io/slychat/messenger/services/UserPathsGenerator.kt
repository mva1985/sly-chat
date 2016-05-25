package io.slychat.messenger.services

import io.slychat.messenger.core.PlatformInfo
import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.div
import java.io.File

class UserPathsGenerator(private val platformInfo: PlatformInfo) {
    companion object {
        val ACCOUNT_INFO_FILENAME = "account-info.json"
    }

    val accountsDir = platformInfo.appFileStorageDirectory / "accounts"
    val startupInfoPath = platformInfo.appFileStorageDirectory / "startup-info.json"

    fun getAccountInfoPath(userId: UserId): File {
        return accountsDir / userId.long.toString() / ACCOUNT_INFO_FILENAME
    }

    fun getPaths(userId: UserId): UserPaths {
        val userAccountDir = accountsDir / userId.long.toString()

        return UserPaths(
            userAccountDir,
            userAccountDir / "keyvault.json",
            userAccountDir / ACCOUNT_INFO_FILENAME,
            userAccountDir / "session-data.json",
            userAccountDir / "db.sqlite3"
        )
    }
}