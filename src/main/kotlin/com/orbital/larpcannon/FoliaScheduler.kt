package com.orbital.larpcannon

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

class FoliaScheduler(private val plugin: Plugin) : CustomScheduler {
    override fun runTimer(runnable: Runnable, delayTicks: Long, periodTicks: Long) {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { _ -> runnable.run() }, delayTicks.coerceAtLeast(1L), periodTicks)
    }

    override fun runAtLocationLater(location: Location, runnable: Runnable, delayTicks: Long) {
        Bukkit.getRegionScheduler().runDelayed(plugin, location, { _ -> runnable.run() }, delayTicks.coerceAtLeast(1L))
    }

    override fun runAtEntityLater(entity: Entity, runnable: Runnable, delayTicks: Long) {
        entity.scheduler.runDelayed(plugin, { _ -> runnable.run() }, null, delayTicks.coerceAtLeast(1L))
    }

    override fun runLater(runnable: Runnable, delayTicks: Long) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ -> runnable.run() }, delayTicks.coerceAtLeast(1L))
    }

    override fun runAsync(runnable: Runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin, { _ -> runnable.run() })
    }

    override fun cancelAllTasks() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin)
        Bukkit.getAsyncScheduler().cancelTasks(plugin)
    }

    override fun runAtLocation(location: Location, runnable: Runnable) {
        Bukkit.getRegionScheduler().execute(plugin, location) { runnable.run() }
    }

    override fun teleportAsync(entity: Entity, location: Location): java.util.concurrent.CompletableFuture<Boolean> {
        return entity.teleportAsync(location)
    }
}
