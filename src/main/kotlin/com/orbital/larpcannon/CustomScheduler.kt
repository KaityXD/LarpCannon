package com.orbital.larpcannon

import org.bukkit.Location
import org.bukkit.entity.Entity

interface CustomScheduler {
    fun runTimer(runnable: Runnable, delayTicks: Long, periodTicks: Long)
    fun runAtLocationLater(location: Location, runnable: Runnable, delayTicks: Long)
    fun runAtEntityLater(entity: Entity, runnable: Runnable, delayTicks: Long)
    fun runLater(runnable: Runnable, delayTicks: Long)
    fun runAsync(runnable: Runnable)
    fun cancelAllTasks()
    fun runAtLocation(location: Location, runnable: Runnable)
    fun teleportAsync(entity: Entity, location: Location): java.util.concurrent.CompletableFuture<Boolean>
}
