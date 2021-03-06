package io.slychat.messenger.services.contacts

import nl.komponents.kovenant.Promise
import java.util.concurrent.TimeUnit

//hack for creating timeout promises
interface PromiseTimerFactory {
    fun run(timeout: Long, timeUnit: TimeUnit): Promise<Unit, Exception>
}