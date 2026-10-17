package io.github.aaroncchung.spoilerblocker

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.aaroncchung.spoilerblocker.data.BlockerRepository
import io.github.aaroncchung.spoilerblocker.data.HiddenNotificationRepository
import io.github.aaroncchung.spoilerblocker.status.StatusNotifier
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android creates one of these when the app's process starts, before any
 * screen or service, and keeps it until the process ends. That makes it the
 * place for objects the whole app shares.
 */
class SpoilerBlockerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Started here and not by a screen, because the process is not
        // always started by one: after a restart of the phone Android starts
        // it to connect the notification listener.
        container.statusNotifier.start(container.blockerRepository.blockers, container.applicationScope)
    }
}

/**
 * The objects the whole app shares. This does by hand what a dependency
 * injection framework would do: anything that needs the repository asks the
 * container for it and so gets the same one.
 */
class AppContainer(context: Context) {

    /**
     * The only [BlockerRepository] in the app. `by lazy` creates it the first
     * time it is asked for, then keeps it.
     */
    val blockerRepository: BlockerRepository by lazy {
        // filesDir is the app's private storage. Other apps cannot read it.
        BlockerRepository(produceFile = { File(context.filesDir, "blockers.json") })
    }

    /** The only [HiddenNotificationRepository] in the app: the "hidden while blocking" list. */
    val hiddenNotificationRepository: HiddenNotificationRepository by lazy {
        HiddenNotificationRepository(
            produceFile = { File(context.filesDir, "hidden_notifications.json") },
        )
    }

    /** Shows the status notification while a blocker is on. */
    val statusNotifier = StatusNotifier(context)

    /**
     * For work that has to finish even if the screen or service that started
     * it has gone, such as saving a hidden notification. It lives as long as
     * the process does and is never cancelled.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/**
 * For ViewModel factories. Android hands a factory a bag of "extras" when it
 * creates a ViewModel, and the application is one of them.
 */
val CreationExtras.appContainer: AppContainer
    get() = (this[APPLICATION_KEY] as SpoilerBlockerApplication).container
