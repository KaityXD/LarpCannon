package com.orbital.larpcannon

import org.bukkit.*
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.stream.Collectors

class OrbitalLarpCannonPlugin : JavaPlugin(), CommandExecutor, Listener, TabCompleter {

    companion object {
        private const val CUSTOM_MODEL_DATA = 12345
        private val STRIKE_TYPES = arrayOf("nuke", "stab", "dogs", "chunkeater", "stasis")
        private const val GITHUB_REPO = "xFairyzz/Orbitalstrike-Plugin"
        private const val CURRENT_VERSION = "v1.6.1"
    }

    private val strikeTNT = HashMap<UUID, Set<UUID>>()
    private val pendingStrikes = HashMap<UUID, String>()
    lateinit var customScheduler: CustomScheduler
    private lateinit var pluginConfig: FileConfiguration

    private var hasUpdate = false
    private var latestVersion = ""

    override fun onEnable() {
        val isFolia = try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }

        customScheduler = if (isFolia) {
            FoliaScheduler(this)
        } else {
            BukkitScheduler(this)
        }

        saveDefaultConfig()
        pluginConfig = config
        setDefaults()
        saveConfig()

        getCommand("orbital")?.let {
            it.setExecutor(this)
            it.setTabCompleter(this)
        }
        server.pluginManager.registerEvents(this, this)

        checkForUpdate()
        customScheduler.runLater(Runnable { sendConsoleUpdate() }, 40L)
    }

    private fun checkForUpdate() {
        customScheduler.runAsync(Runnable {
            try {
                val releasesUrl = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
                val url = URL(releasesUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    logger.warning("§e[OrbitalLarpCannon] GitHub API ERROR: HTTP $responseCode")
                    return@Runnable
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val json = response.toString()
                val tagName = extractTagName(json)

                if (tagName != null) {
                    val normalizedTag = if (tagName.startsWith("v", ignoreCase = true)) tagName.substring(1) else tagName
                    val normalizedCurrent = if (CURRENT_VERSION.startsWith("v", ignoreCase = true)) CURRENT_VERSION.substring(1) else CURRENT_VERSION

                    if (!normalizedTag.equals(normalizedCurrent, ignoreCase = true)) {
                        hasUpdate = true
                        latestVersion = tagName
                    }
                }
            } catch (e: Exception) {
                logger.warning("§e[OrbitalLarpCannon] Update-Check Failed: ${e.message}")
            }
        })
    }

    private fun extractTagName(json: String): String? {
        return try {
            val tagIndex = json.indexOf("\"tag_name\":\"")
            if (tagIndex == -1) return null

            val start = tagIndex + 12
            val end = json.indexOf("\"", start)
            if (end == -1) return null

            json.substring(start, end)
        } catch (e: Exception) {
            null
        }
    }

    private fun sendUpdateNotification(player: Player) {
        if (hasUpdate && player.isOp) {
            player.sendMessage("§c[OrbitalLarpCannon] §fUpdate available! §cCurrent: $CURRENT_VERSION → §aLatest: $latestVersion")
            player.sendMessage("§cDownload: §Fhttps://modrinth.com/plugin/orbitalstrike-plugin")
        }
    }

    private fun sendConsoleUpdate() {
        if (hasUpdate) {
            logger.warning("\u001B[31mUPDATE AVAILABLE! Current: $CURRENT_VERSION → Latest: $latestVersion\u001B[0m")
            logger.warning("\u001B[31mDownload: https://modrinth.com/plugin/orbitalstrike-plugin\u001B[0m")
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        customScheduler.runLater(Runnable { sendUpdateNotification(player) }, 20L)
    }

    override fun onDisable() {
        strikeTNT.clear()
        pendingStrikes.clear()
        customScheduler.cancelAllTasks()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cOnly players can use this command.")
            return true
        }

        if (!hasPermission(sender)) {
            sendMessage(sender, "no-permission", emptyMap())
            return true
        }

        if (args.isEmpty()) {
            sendMessage(sender, "usage", mapOf("{CMD}" to "/orbital <nuke|stab|dogs|stasis|reload>"))
            return true
        }

        val type = args[0].lowercase()
        
        if (type == "reload") {
            reloadConfig()
            pluginConfig = config
            sender.sendMessage("§a[OrbitalLarpCannon] Configuration reloaded.")
            return true
        }
        if (!isValidStrikeType(type)) {
            sendMessage(sender, "invalid-type", emptyMap())
            return true
        }

        if (type == "stasis") {
            if (args.size != 4) {
                sendMessage(sender, "usage", mapOf("{CMD}" to "/orbital stasis <x> <y> <z>"))
                return true
            }
            try {
                val x = args[1].toDouble()
                val y = args[2].toDouble()
                val z = args[3].toDouble()
                giveStrikeRod(sender, type, x, y, z)
            } catch (e: NumberFormatException) {
                sender.sendMessage("§cInvalid coordinates!")
                return true
            }
        } else {
            if (args.size != 1) {
                sendMessage(sender, "usage", mapOf("{CMD}" to "/orbital <nuke|stab|dogs>"))
                return true
            }
            giveStrikeRod(sender, type)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        if (!command.name.equals("orbital", ignoreCase = true)) {
            return null
        }

        if (args.size == 1) {
            val input = args[0].lowercase()
            val options = STRIKE_TYPES.toMutableList()
            options.add("reload")
            return options.filter { it.startsWith(input) }.sorted()
        }

        if (args.size > 1) {
            val type = args[0].lowercase()
            if (type.startsWith("stasis")) {
                if (args.size >= 5) {
                    return emptyList()
                }
                val completions = ArrayList<String>()
                if (args.size == 2) completions.add("<x>")
                if (args.size == 3) completions.add("<y>")
                if (args.size == 4) completions.add("<z>")
                return completions
            }
        }

        return emptyList()
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!event.action.name.contains("RIGHT") || !event.hasItem()) {
            return
        }

        val item = event.item ?: return
        if (!isStrikeRod(item)) {
            return
        }

        val type = getStrikeType(item) ?: return
        val player = event.player

        if (type == "chunkeater") {
            return
        }

        if (type == "stasis") {
            val throwRod = pluginConfig.getBoolean("rod.throw-rod", true)
            if (throwRod) {
                pendingStrikes[player.uniqueId] = type
            } else {
                executeStasisStrike(player, item)
                event.isCancelled = true
            }
            return
        }

        val target = getTargetLocation(player)
        if (target == null) {
            sendMessage(player, "no-target", emptyMap())
            return
        }

        val throwRod = pluginConfig.getBoolean("rod.throw-rod", true)
        if (throwRod) {
            pendingStrikes[player.uniqueId] = type
        } else {
            executeStrike(player, item, type, target)
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerFish(event: PlayerFishEvent) {
        if (!pluginConfig.getBoolean("rod.throw-rod", true)) {
            return
        }

        val player = event.player
        val playerId = player.uniqueId

        if (!pendingStrikes.containsKey(playerId)) {
            return
        }

        val state = event.state
        if (state != PlayerFishEvent.State.REEL_IN &&
            state != PlayerFishEvent.State.FAILED_ATTEMPT &&
            state != PlayerFishEvent.State.CAUGHT_ENTITY &&
            state != PlayerFishEvent.State.IN_GROUND &&
            state != PlayerFishEvent.State.BITE) {
            return
        }

        val type = pendingStrikes.remove(playerId) ?: return

        if (type == "stasis") {
            val mainHand = player.inventory.itemInMainHand
            val offHand = player.inventory.itemInOffHand

            var consumed = false
            if (mainHand.type == Material.FISHING_ROD && isStrikeRod(mainHand)) {
                executeStasisStrike(player, mainHand)
                mainHand.amount = mainHand.amount - 1
                consumed = true
            } else if (offHand.type == Material.FISHING_ROD && isStrikeRod(offHand)) {
                executeStasisStrike(player, offHand)
                offHand.amount = offHand.amount - 1
                consumed = true
            }

            if (consumed) {
                player.playSound(player.location, Sound.ENTITY_PLAYER_TELEPORT, 1.0f, 1.0f)
                player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
            }
            return
        }

        val mainHand = player.inventory.itemInMainHand
        val offHand = player.inventory.itemInOffHand

        var consumed = false
        if (mainHand.type == Material.FISHING_ROD && isStrikeRod(mainHand)) {
            mainHand.amount = mainHand.amount - 1
            consumed = true
        } else if (offHand.type == Material.FISHING_ROD && isStrikeRod(offHand)) {
            offHand.amount = offHand.amount - 1
            consumed = true
        }

        if (consumed) {
            player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
        }

        val target = getTargetLocation(player) ?: return
        executeStrike(player, ItemStack(Material.FISHING_ROD), type, target)
    }

    @EventHandler
    fun onTNTLand(event: EntityChangeBlockEvent) {
        val tnt = event.entity as? TNTPrimed ?: return
        val tntId = tnt.uniqueId
        if (!isTrackedTNT(tntId)) {
            return
        }

        event.isCancelled = true

        if (event.block.type.isSolid) {
            removeFromTracking(tntId)
            scheduleExplosion(tnt)
        }
    }

    @EventHandler
    fun onArmorStandPlace(event: EntityPlaceEvent) {
        val armorStand = event.entity as? ArmorStand ?: return
        val player = event.player ?: return

        var item = player.inventory.itemInMainHand
        if (item.type != Material.ARMOR_STAND) {
            item = player.inventory.itemInOffHand
        }

        if (!isStrikeRod(item) || getStrikeType(item) != "chunkeater") {
            return
        }

        val target = armorStand.location
        armorStand.remove()
        sendMessage(player, "incoming", mapOf("{TYPE}" to "CHUNKEATER"))
        executeChunkEaterStrike(player, target)
    }

    private fun executeChunkEaterStrike(player: Player, target: Location) {
        customScheduler.runAtLocation(target, Runnable {
            spawnChunkEater(target.world, target)
        })
    }

    private fun executeStasisStrike(player: Player, item: ItemStack) {
        val meta = item.itemMeta ?: return
        val keyX = NamespacedKey(this, "stasis_x")
        val keyY = NamespacedKey(this, "stasis_y")
        val keyZ = NamespacedKey(this, "stasis_z")

        if (!meta.persistentDataContainer.has(keyX, PersistentDataType.DOUBLE) ||
            !meta.persistentDataContainer.has(keyY, PersistentDataType.DOUBLE) ||
            !meta.persistentDataContainer.has(keyZ, PersistentDataType.DOUBLE)) {
            return
        }

        val x = meta.persistentDataContainer.get(keyX, PersistentDataType.DOUBLE) ?: 0.0
        val y = meta.persistentDataContainer.get(keyY, PersistentDataType.DOUBLE) ?: 0.0
        val z = meta.persistentDataContainer.get(keyZ, PersistentDataType.DOUBLE) ?: 0.0

        val teleportLoc = Location(player.world, x, y, z)
        
        customScheduler.teleportAsync(player, teleportLoc).thenAccept { success ->
            if (success) {
                player.playSound(teleportLoc, Sound.ENTITY_ENDER_PEARL_THROW, 1.0f, 1.0f)
            }
        }

        item.amount = item.amount - 1
    }

    private fun executeStrike(player: Player, item: ItemStack, type: String, target: Location) {
        sendMessage(player, "incoming", mapOf("{TYPE}" to type.uppercase()))

        if (type != "chunkeater" && !pluginConfig.getBoolean("rod.throw-rod", true)) {
            consumeRodDelayed(player, item)
        }

        val strikeId = UUID.randomUUID()
        val tntList = HashSet<UUID>()
        strikeTNT[strikeId] = tntList

        customScheduler.runAtLocation(target, Runnable {
            val world = target.world
            when (type) {
                "nuke" -> spawnNuke(world, target, strikeId, tntList)
                "stab" -> spawnStab(world, target)
                "dogs" -> spawnDogs(world, target, player)
                "chunkeater" -> spawnChunkEater(world, target)
            }
        })

        customScheduler.runAtLocationLater(target, Runnable {
            strikeTNT.remove(strikeId)
        }, 200L)
    }

    private fun consumeRodDelayed(player: Player, originalItem: ItemStack) {
        val displayName = originalItem.itemMeta?.displayName ?: return

        customScheduler.runAtEntityLater(player, Runnable {
            if (!consumeFromHand(player.inventory.itemInMainHand, displayName, player)) {
                consumeFromHand(player.inventory.itemInOffHand, displayName, player)
            }
        }, 1L)
    }

    private fun consumeFromHand(hand: ItemStack, displayName: String, player: Player): Boolean {
        if (hand.type == Material.FISHING_ROD && hand.hasItemMeta() &&
            hand.itemMeta?.displayName == displayName) {
            hand.amount = hand.amount - 1
            player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
            return true
        }
        return false
    }

    private fun spawnNuke(world: World, center: Location, strikeId: UUID, tntList: HashSet<UUID>) {
        val rings = pluginConfig.getInt("nuke.rings", 10)
        val height = center.y + pluginConfig.getInt("nuke.height", 80)
        val yield = pluginConfig.getDouble("nuke.yield", 6.0).toFloat()
        val baseTnt = pluginConfig.getInt("nuke.tnt-per-ring-base", 40)
        val increase = pluginConfig.getInt("nuke.tnt-per-ring-increase", 2)
        val centerTnt = pluginConfig.getBoolean("nuke.center-tnt", true)
        val animatedRings = pluginConfig.getBoolean("nuke.Animated-rings", true)

        val centerLoc = Location(world, center.x + 0.5, height, center.z + 0.5)

        if (animatedRings) {
            if (centerTnt) {
                val ct = world.spawnEntity(centerLoc.clone(), EntityType.TNT) as TNTPrimed
                val fuseFallbackTicks = pluginConfig.getInt("nuke.fuse-fallback-ticks", 160)
                ct.fuseTicks = fuseFallbackTicks
                ct.velocity = Vector(0, 0, 0)
                ct.setGravity(true)
                ct.yield = yield
                ct.isInvulnerable = true
                val centerId = ct.uniqueId
                tntList.add(centerId)
            }

            for (ring in 1..rings) {
                val delayTicks = 1L + (ring - 1) * 2L
                customScheduler.runAtLocationLater(centerLoc, Runnable {
                    val radius = ring * 4.0
                    val originalCount = baseTnt + ring * increase
                    var tntCount = originalCount

                    val splitRings = pluginConfig.getBoolean("nuke.full-rings", false)
                    if (splitRings && originalCount > 15) {
                        val minRemove = pluginConfig.getDouble("nuke.full-rings-min-remove", 0.20)
                        val maxRemove = pluginConfig.getDouble("nuke.full-rings-max-remove", 0.35)
                        val removePercent = minRemove + (Math.random() * (maxRemove - minRemove))
                        var remove = (originalCount * removePercent).toInt()
                        remove = remove.coerceAtMost(originalCount - 10)
                        tntCount = originalCount - remove
                    }

                    val step = 360.0 / originalCount
                    var indices = (0 until originalCount).toList()

                    if (splitRings && tntCount < originalCount) {
                        indices = indices.shuffled().subList(0, tntCount).sorted()
                    }

                    for (idx in indices) {
                        val angle = idx * step + (ring * 10)
                        val tx = center.x + radius * Math.cos(Math.toRadians(angle))
                        val tz = center.z + radius * Math.sin(Math.toRadians(angle))
                        val targetX = Math.round(tx * 10) / 10.0 + 0.5
                        val targetZ = Math.round(tz * 10) / 10.0 + 0.5

                        val offsetX = (Math.random() - 0.5) * 0.05
                        val offsetY = idx * 0.0005
                        val offsetZ = (Math.random() - 0.5) * 0.05
                        val spawnLoc = centerLoc.clone().add(offsetX, offsetY, offsetZ)

                        val tnt = world.spawnEntity(spawnLoc, EntityType.TNT) as TNTPrimed
                        val fuseFallbackTicks = pluginConfig.getInt("nuke.fuse-fallback-ticks", 160)
                        tnt.fuseTicks = fuseFallbackTicks
                        tnt.velocity = getVector(targetX, centerLoc, targetZ)
                        tnt.setGravity(true)
                        tnt.yield = yield
                        tnt.isInvulnerable = true
                        val tntUuid = tnt.uniqueId
                        tntList.add(tntUuid)
                    }
                }, delayTicks)
            }
        } else {
            if (centerTnt) {
                spawnNukeTNT(world, centerLoc.clone(), yield, strikeId, tntList)
            }
            for (ring in 1..rings) {
                val delayTicks = 1L + (ring - 1) * 2L
                customScheduler.runAtLocationLater(centerLoc, Runnable {
                    val radius = ring * 4.0
                    val tntCount = baseTnt + ring * increase
                    val step = 360.0 / tntCount
                    val startAngle = ring * 13.0
                    for (i in 0 until tntCount) {
                        val angle = startAngle + i * step
                        val x = center.x + radius * Math.cos(Math.toRadians(angle))
                        val z = center.z + radius * Math.sin(Math.toRadians(angle))
                        val roundedX = Math.round(x * 10) / 10.0 + 0.5
                        val roundedZ = Math.round(z * 10) / 10.0 + 0.5
                        val loc = Location(world, roundedX, height, roundedZ)
                        spawnNukeTNT(world, loc, yield, strikeId, tntList)
                    }
                }, delayTicks)
            }
        }
    }

    private fun getVector(finalTargetX: Double, centerLoc: Location, finalTargetZ: Double): Vector {
        val deltaX = finalTargetX - centerLoc.x
        val deltaZ = finalTargetZ - centerLoc.z
        val distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ)
        val speed = distance / 30.0
        return Vector(deltaX / distance * speed, 0.0, deltaZ / distance * speed)
    }

    private fun spawnNukeTNT(world: World, loc: Location, yield: Float, strikeId: UUID, tntList: HashSet<UUID>) {
        val cx = loc.blockX shr 4
        val cz = loc.blockZ shr 4

        if (!world.isChunkLoaded(cx, cz)) return

        val tnt = world.spawnEntity(loc, EntityType.TNT) as TNTPrimed
        val fuseFallbackTicks = pluginConfig.getInt("nuke.fuse-fallback-ticks", 160)
        tnt.fuseTicks = fuseFallbackTicks
        tnt.velocity = Vector(0.0, -0.8, 0.0)
        tnt.setGravity(true)
        tnt.yield = yield
        tnt.isInvulnerable = true

        val tntId = tnt.uniqueId
        tntList.add(tntId)
    }

    private fun spawnStab(world: World, center: Location) {
        val ground = findGroundLevel(world, center)
        val yield = pluginConfig.getDouble("stab.yield", 8.0).toFloat()
        val offset = pluginConfig.getDouble("stab.tnt-offset", 0.3)

        var y = ground.blockY
        val minY = world.minHeight

        while (y >= minY) {
            val loc = Location(world, ground.x, y.toDouble(), ground.z)
            spawnTNTAt(world, loc.clone().add(offset, 0.0, offset), yield)
            spawnTNTAt(world, loc.clone().subtract(offset, 0.0, offset), yield)
            y -= 2
        }
    }

    private fun spawnTNTAt(world: World, loc: Location, yield: Float) {
        if (loc.block.isLiquid) return

        val tnt = world.spawnEntity(loc, EntityType.TNT) as TNTPrimed
        tnt.fuseTicks = 0
        tnt.yield = yield
    }

    private fun spawnDogs(world: World, center: Location, owner: Player) {
        val count = pluginConfig.getInt("dogs.count", 50)
        val radius = pluginConfig.getDouble("dogs.radius", 5.0)
        val durationTicks = pluginConfig.getInt("dogs.effect-duration", 2400)

        val effectStrings = pluginConfig.getStringList("dogs.effects")
        val effects = ArrayList<PotionEffect>()

        for (i in 0 until Math.min(effectStrings.size, 3)) {
            val entry = effectStrings[i].trim().uppercase()
            val parts = entry.split(":")
            if (parts.size != 2) continue

            val type = PotionEffectType.getByName(parts[0]) ?: continue
            var amplifier = try {
                parts[1].toInt() - 1
            } catch (e: NumberFormatException) {
                continue
            }

            if (amplifier < 0) amplifier = 0
            effects.add(PotionEffect(type, durationTicks, amplifier))
        }

        if (effects.isEmpty()) {
            effects.add(PotionEffect(PotionEffectType.SPEED, durationTicks, 1))
        }

        val ground = findGroundLevel(world, center)

        for (i in 0 until count) {
            val angle = Math.random() * 360
            val dist = Math.random() * radius
            val x = ground.x + dist * Math.cos(Math.toRadians(angle))
            val z = ground.z + dist * Math.sin(Math.toRadians(angle))

            val spawnLoc = findGroundLevel(world, Location(world, x, ground.y, z))
            if (spawnLoc.block.isLiquid) continue

            val wolf = world.spawnEntity(spawnLoc, EntityType.WOLF) as Wolf
            wolf.isTamed = true
            wolf.owner = owner
            wolf.isSitting = false
            wolf.collarColor = DyeColor.RED

            val wolfArmor = ItemStack(Material.WOLF_ARMOR)
            wolf.equipment?.setItem(EquipmentSlot.BODY, wolfArmor)

            for (effect in effects) {
                wolf.addPotionEffect(effect)
            }
        }
    }

    private fun spawnChunkEater(world: World, center: Location) {
        val ground = findGroundLevel(world, center)
        val chunk = ground.chunk
        val minY = world.minHeight
        val maxY = world.maxHeight

        val chunkX = chunk.x shl 4
        val chunkZ = chunk.z shl 4

        val tntAmount = 250
        val random = Random()

        for (i in 0 until tntAmount) {
            val x = chunkX + random.nextDouble() * 16
            val z = chunkZ + random.nextDouble() * 16
            val y = center.y + 35

            val tntLoc = Location(world, x, y, z)
            val tnt = world.spawnEntity(tntLoc, EntityType.TNT) as TNTPrimed
            tnt.fuseTicks = 90
            tnt.yield = 2.0f
        }

        customScheduler.runAtLocationLater(ground, Runnable {
            if (!chunk.isLoaded) chunk.load()
            for (x in 0 until 16) {
                for (z in 0 until 16) {
                    for (y in minY + 1 until maxY) {
                        val block = chunk.getBlock(x, y, z)
                        if (block.type != Material.BEDROCK) {
                            block.setType(Material.AIR, false)
                        }
                    }
                }
            }
        }, 100L)
    }

    private fun hasPermission(player: Player): Boolean {
        return player.hasPermission(pluginConfig.getString("permission", "orbital.use") ?: "orbital.use")
    }

    private fun isValidStrikeType(type: String): Boolean {
        return STRIKE_TYPES.contains(type)
    }

    private fun giveStrikeRod(player: Player, type: String) {
        val rod = createStrikeRod(type)
        player.inventory.addItem(rod)
        sendMessage(player, "received", mapOf("{TYPE}" to type.uppercase()))
    }

    private fun giveStrikeRod(player: Player, type: String, x: Double, y: Double, z: Double) {
        val rod = createStrikeRod(type, x, y, z)
        player.inventory.addItem(rod)
        sendMessage(player, "received", mapOf("{TYPE}" to type.uppercase()))
    }

    private fun createStrikeRod(type: String, x: Double = 0.0, y: Double = 0.0, z: Double = 0.0): ItemStack {
        val material = if (type == "chunkeater") Material.ARMOR_STAND else Material.FISHING_ROD
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item

        val displayName = when (type) {
            "nuke" -> "Nuke shot"
            "stab" -> "Stab shot"
            "dogs" -> "Dogs"
            "chunkeater" -> "Chunk Eater"
            "stasis" -> "Stasis"
            else -> "Orbital Strike Rod"
        }

        meta.setDisplayName(displayName)
        meta.setCustomModelData(CUSTOM_MODEL_DATA)
        
        if (type == "stasis") {
            val keyX = NamespacedKey(this, "stasis_x")
            val keyY = NamespacedKey(this, "stasis_y")
            val keyZ = NamespacedKey(this, "stasis_z")
            meta.persistentDataContainer.set(keyX, PersistentDataType.DOUBLE, x)
            meta.persistentDataContainer.set(keyY, PersistentDataType.DOUBLE, y)
            meta.persistentDataContainer.set(keyZ, PersistentDataType.DOUBLE, z)
        }
        item.itemMeta = meta

        if (material == Material.FISHING_ROD) {
            item.durability = 63.toShort()
        }

        return item
    }

    private fun isStrikeRod(item: ItemStack?): Boolean {
        if (item == null || !item.hasItemMeta()) return false
        val meta = item.itemMeta ?: return false
        if (!meta.hasDisplayName() || !meta.hasCustomModelData() || meta.customModelData != CUSTOM_MODEL_DATA) {
            return false
        }
        val type = item.type
        return type == Material.FISHING_ROD || type == Material.ARMOR_STAND
    }

    private fun getStrikeType(item: ItemStack): String? {
        val displayName = item.itemMeta?.displayName ?: return null
        return when (displayName) {
            "Nuke shot" -> "nuke"
            "Stab shot" -> "stab"
            "Dogs" -> "dogs"
            "Chunk Eater" -> "chunkeater"
            "Stasis" -> "stasis"
            else -> null
        }
    }

    private fun getTargetLocation(player: Player): Location? {
        val distance = pluginConfig.getInt("rod.distance", 100)
        val result = player.rayTraceBlocks(distance.toDouble())
        if (result == null || result.hitBlock == null) return null
        return result.hitBlock!!.location.add(0.0, 60.0, 0.0)
    }

    private fun findGroundLevel(world: World, start: Location): Location {
        val ground = start.clone()
        while (ground.y > world.minHeight && ground.block.type.isAir) {
            ground.subtract(0.0, 1.0, 0.0)
        }
        return ground.add(0.0, 1.0, 0.0)
    }

    private fun scheduleExplosion(tnt: TNTPrimed) {
        if (!tnt.isDead) {
            tnt.fuseTicks = 1
        }
    }

    private fun isTrackedTNT(tntId: UUID): Boolean {
        return strikeTNT.values.any { it.contains(tntId) }
    }

    private fun removeFromTracking(tntId: UUID) {
        strikeTNT.values.forEach { (it as? MutableSet)?.remove(tntId) }
    }

    private fun sendMessage(player: Player, key: String, placeholders: Map<String, String>) {
        if (!pluginConfig.getBoolean("messages-enabled", true)) return
        val path = "messages.$key"
        var message = pluginConfig.getString(path) ?: return
        if (message.isEmpty()) return

        for ((pKey, pVal) in placeholders) {
            message = message.replace(pKey, pVal)
        }
        player.sendMessage(message)
    }

    private fun setDefaults() {
        pluginConfig.addDefault("messages-enabled", true)
        pluginConfig.addDefault("permission", "orbital.use")

        setRodDefaults()
        setNukeDefaults()
        setStabDefaults()
        setDogsDefaults()

        pluginConfig.options().copyDefaults(true)
    }

    private fun setRodDefaults() {
        val rod = HashMap<String, Any>()
        rod["distance"] = 100
        rod["throw-rod"] = true
        pluginConfig.addDefault("rod", rod)
    }

    private fun setNukeDefaults() {
        val nuke = HashMap<String, Any>()
        nuke["rings"] = 10
        nuke["height"] = 80
        nuke["yield"] = 6.0
        nuke["tnt-per-ring-base"] = 40
        nuke["tnt-per-ring-increase"] = 2
        nuke["center-tnt"] = CenterTnt
        nuke["fuse-fallback-ticks"] = 160
        nuke["Animated-rings"] = true
        nuke["full-rings"] = false
        pluginConfig.addDefault("nuke", nuke)
    }

    // Workaround for compile issues with primitive boolean names
    private val CenterTnt: Boolean
        get() = pluginConfig.getBoolean("nuke.center-tnt", true)

    private fun setStabDefaults() {
        val stab = HashMap<String, Any>()
        stab["yield"] = 8.0
        stab["tnt-offset"] = 0.3
        pluginConfig.addDefault("stab", stab)
    }

    private fun setDogsDefaults() {
        val dogs = HashMap<String, Any>()
        dogs["count"] = 50
        dogs["radius"] = 5.0
        dogs["effect-duration"] = 2400

        val effects = ArrayList<String>()
        effects.add("SPEED:1")
        effects.add("STRENGTH:2")
        dogs["effects"] = effects

        pluginConfig.addDefault("dogs", dogs)
    }
}
