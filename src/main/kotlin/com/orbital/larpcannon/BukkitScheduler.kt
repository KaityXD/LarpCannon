package com.orbital.larpcannon

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

class BukkitScheduler(private val plugin: Plugin) : CustomScheduler {
    override fun runTimer(runnable: Runnable, delayTicks: Long, periodTicks: Long) {
        Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks)
    }

    override fun runAtLocationLater(location: Location, runnable: Runnable, delayTicks: Long) {
        Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks)
    }

    override fun runAtEntityLater(entity: Entity, runnable: Runnable, delayTicks: Long) {
        Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks)
    }

    override fun runLater(runnable: Runnable, delayTicks: Long) {
        Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks)
    }

    override fun runAsync(runnable: Runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable)
    }

    override fun cancelAllTasks() {
        Bukkit.getScheduler().cancelTasks(plugin)
    }

    override fun runAtLocation(location: Location, runnable: Runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable)
    }

    override fun teleportAsync(entity: Entity, location: Location): java.util.concurrent.CompletableFuture<Boolean> {
        val future = java.util.concurrent.CompletableFuture<Boolean>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            future.complete(entity.teleport(location))
        })
        return future
    }
}
