package com.panomc.plugins.market.runtime

import com.panomc.plugins.market.db.SchemaVerifier

/**
 * The runtime gate of 00 section 8.9. The host mounts the routes of a plugin when it is loaded and keeps them
 * mounted when its start fails or it is stopped, so market answers for itself: every market route asks [state] first
 * (`MK-021` puts that into the route base classes).
 *
 * - [State.STOPPED] is the initial value and the first thing `onStop` / `onDisable` set;
 * - [State.STARTING] while `MarketBootstrap.run()` works;
 * - [State.READY] only at the end of a bootstrap whose schema verification passed;
 * - [State.DEGRADED] when verification failed (or the bootstrap itself broke): storefront and checkout answer
 *   `STORE_UNAVAILABLE`, `GET /health` lists [health].
 *
 * One process-wide object: the plugin classloader is dropped with the plugin, so nothing survives a reload.
 */
object MarketRuntime {
    enum class State { STOPPED, STARTING, READY, DEGRADED }

    /** Optional host classes market probes once at start and switches the feature off without (00 section 8.9). */
    data class HostCapabilities(val mail: Boolean, val notifications: Boolean) {
        /** `health.mail`: `"OK"` or `"HOST_TOO_OLD"`. */
        val mailStatus: String get() = if (mail) "OK" else "HOST_TOO_OLD"
    }

    /** What `GET /health` reports besides the state: what the verifier found and what the bootstrap swallowed. */
    data class Health(
        val state: State,
        val problems: List<String>,
        val unfixed: Map<String, Long>,
        val bootstrapErrors: List<String>,
        val capabilities: HostCapabilities
    )

    const val MAIL_OPTIONS_CLASS = "com.panomc.platform.mail.MailOptions"
    const val NOTIFICATION_REGISTRY_CLASS = "com.panomc.platform.notification.NotificationTypeRegistry"

    @Volatile
    var state: State = State.STOPPED
        private set

    @Volatile
    var capabilities: HostCapabilities = HostCapabilities(mail = false, notifications = false)
        private set

    @Volatile
    private var verification: SchemaVerifier.Result? = null

    @Volatile
    private var bootstrapErrors: List<String> = emptyList()

    val isReady: Boolean get() = state == State.READY

    internal fun starting() {
        verification = null
        bootstrapErrors = emptyList()
        state = State.STARTING
    }

    internal fun finish(result: SchemaVerifier.Result?, errors: List<String>, degraded: Boolean) {
        verification = result
        bootstrapErrors = errors
        state = if (degraded) State.DEGRADED else State.READY
    }

    /** First statement of `onStop` / `onDisable`. */
    fun stopped() {
        state = State.STOPPED
    }

    /** Back to the verdict of the one bootstrap after a stop -> start of the same plugin instance. */
    internal fun resume(degraded: Boolean) {
        state = if (degraded) State.DEGRADED else State.READY
    }

    fun health(): Health = Health(
        state = state,
        problems = verification?.describe().orEmpty(),
        unfixed = verification?.unfixed.orEmpty(),
        bootstrapErrors = bootstrapErrors,
        capabilities = capabilities
    )

    /** Probes the optional host classes with `Class.forName` (never initialising them) and remembers the answer. */
    fun probeHostCapabilities(loader: ClassLoader? = MarketRuntime::class.java.classLoader): HostCapabilities {
        fun present(name: String) = try {
            Class.forName(name, false, loader)
            true
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: LinkageError) {
            false
        }

        return HostCapabilities(present(MAIL_OPTIONS_CLASS), present(NOTIFICATION_REGISTRY_CLASS)).also { capabilities = it }
    }

    /** Test seam: back to the initial value. */
    internal fun reset() {
        state = State.STOPPED
        verification = null
        bootstrapErrors = emptyList()
        capabilities = HostCapabilities(mail = false, notifications = false)
    }
}
