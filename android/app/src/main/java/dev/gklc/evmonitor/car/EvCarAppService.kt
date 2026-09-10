package dev.gklc.evmonitor.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class EvCarAppService : CarAppService() {
    // personal sideloaded build — accept any host (Play builds should ship an allow-list)
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = EvSession()
}

class EvSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = DashboardScreen(carContext)
}
