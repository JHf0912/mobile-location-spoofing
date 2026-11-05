package com.snake.mobilespoofing

import android.app.Application
import com.snake.mobilespoofing.core.location.AppStartupCoordinator
import com.snake.mobilespoofing.core.location.LocationManagerMockEngine
import com.snake.mobilespoofing.core.location.ProviderLeaseStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Runs the cold-start provider cleanup.
 *
 * A `LocationManager` test provider lives in `system_server` and survives this process. If the app
 * was killed (crash, LMK, force-stop) while a session was running, the device keeps reporting the
 * last mock position to every app. The only recovery inside the app is this cleanup on the next
 * process start; force-stop itself never runs `onTerminate`.
 *
 * The cleanup runs on [Dispatchers.IO] and is serialized with a service start through
 * [startupCoordinator]. The service joins it before starting the runner, so the cleanup cannot
 * remove a provider the new session just registered.
 */
@HiltAndroidApp
class SpoofingApp : Application() {

    @Inject lateinit var leaseStore: ProviderLeaseStore
    @Inject lateinit var locationManagerEngine: LocationManagerMockEngine

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Null until [onCreate]. The service uses it to mark its start and join cleanup; a null value
     * only happens in tests or if a component is created before the application.
     */
    var startupCoordinator: AppStartupCoordinator? = null
        private set

    override fun onCreate() {
        super.onCreate()
        startupCoordinator = AppStartupCoordinator(
            leaseStore = leaseStore,
            locationManagerEngine = locationManagerEngine,
            packageName = packageName,
            scope = applicationScope,
        )
    }
}
