package io.papermc.blisssmp

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.HashMap
import java.util.UUID
import kotlin.compareTo
import kotlin.text.compareTo
import kotlin.text.set

class FluxGem(private val plugin: BlissSMPPlugin) : Listener {

    private val MAX_LIVES = 10
    private val DEFAULT_LIVES = 5
    private val fluxGemKey = NamespacedKey(plugin, "flux_gem")
    private val flowStateMap = HashMap<UUID, Int>()
    private val lastActionMap = HashMap<UUID, Long>()
    private val groundCooldowns = HashMap<UUID, Long>()
    private val flashbangCooldowns = HashMap<UUID, Long>()
    private val kineticBurstCooldowns = HashMap<UUID, Long>()
    private val fluxBeamCharging = HashMap<UUID, Int>()
    private val frozenPlayers = HashMap<UUID, Long>()
    private val fluxBeamUnlockedKey = NamespacedKey(plugin, "flux_beam_unlocked")
    private val fluxBeamEnergyKey = NamespacedKey(plugin, "flux_beam_energy")
    private val ENERGY_REQUIRED_FOR_BEAM = 10
    private val fluxBeamCooldowns = HashMap<UUID, Long>()

    private val GROUND_COOLDOWN = 30 * 1000L
    private val FLASHBANG_COOLDOWN = 5 * 1000L
    private val KINETIC_BURST_COOLDOWN = 20 * 1000L
    private val FREEZE_DURATION = 3 * 1000L
    private val TIER_2_LIVES_REQUIREMENT = 7
    private val FLOW_STATE_DECAY_RATE = 1
    private val FLOW_STATE_MAX = 50
    private val FLUX_BEAM_COOLDOWN = 60 * 1000L

    private val random = java.util.Random()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        startFlowStateChecker()
        startFrozenPlayersChecker()
    }

    fun createFluxGem(): ItemStack {
        val fluxGem = ItemStack(Material.CYAN_CANDLE, 1)
        val meta = fluxGem.itemMeta

        if (meta != null) {
            meta.setDisplayName("${ChatColor.AQUA}${ChatColor.BOLD}Flux Gem")
            meta.lore = listOf(
                "${ChatColor.GRAY}A mystical gem that flows with aquatic energy.",
                "",
                "${ChatColor.BLUE}Lives: ${ChatColor.WHITE}$DEFAULT_LIVES/$MAX_LIVES",
                "${ChatColor.BLUE}Flux Beam: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ENERGY_REQUIRED_FOR_BEAM Energy)",
                "",
                "${ChatColor.BLUE}Passives:",
                "${ChatColor.WHITE}• PRISTINE: 15% of attacks will phase through you",
                "${ChatColor.WHITE}• Flow State: Actions become faster the more they're performed",
                "${ChatColor.WHITE}• Shocking Chance: Chance to stun enemies with arrows",
                "${ChatColor.WHITE}• Tireless: Immunity to weakness, slowness & hunger",
                "${ChatColor.WHITE}• Charged: Increased swim speed and water breathing",
                "",
                "${ChatColor.BLUE}Powers:",
                "${ChatColor.WHITE}• Frost Flash: Blinds & freezes nearby entities (Sneak + Left-Click)",
                "${ChatColor.WHITE}• Kinetic Burst: Launch yourself into the air (Right-Click)",
                "${ChatColor.WHITE}• Flux Beam: Powerful energy beam (Sneak + Right-Click) [Needs to be unlocked]",
                "",
                "${ChatColor.YELLOW}• Kill players to gain lives (Max: ${MAX_LIVES})",
                "${ChatColor.YELLOW}• Drag Bottled Of Energy onto gem to unlock Flux Beam",
                "${ChatColor.RED}• 0 Lives = Server Ban"
            )

            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true)
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS)
            meta.persistentDataContainer.set(fluxGemKey, PersistentDataType.BYTE, 1)
            meta.persistentDataContainer.set(plugin.gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
            meta.persistentDataContainer.set(fluxBeamEnergyKey, PersistentDataType.INTEGER, 0)
            meta.persistentDataContainer.set(fluxBeamUnlockedKey, PersistentDataType.BYTE, 0)

            fluxGem.itemMeta = meta
        }

        return fluxGem
    }

    private fun isFluxBeamUnlocked(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.getOrDefault(fluxBeamUnlockedKey, PersistentDataType.BYTE, 0) == 1.toByte()
    }

    private fun unlockFluxBeam(item: ItemStack) {
        val meta = item.itemMeta
        if (meta != null) {
            meta.persistentDataContainer.set(fluxBeamUnlockedKey, PersistentDataType.BYTE, 1)

            val lore = meta.lore
            if (lore != null) {
                val newLore = lore.toMutableList()
                for (i in newLore.indices) {
                    if (newLore[i].contains("Flux Beam:")) {
                        newLore[i] = "${ChatColor.BLUE}Flux Beam: ${ChatColor.GREEN}Unlocked"
                        break
                    }
                }
                meta.lore = newLore
            }

            item.itemMeta = meta
        }
    }

    private fun getFluxBeamEnergy(item: ItemStack): Int {
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(fluxBeamEnergyKey, PersistentDataType.INTEGER, 0)
    }

    private fun updateFluxBeamEnergy(item: ItemStack, count: Int) {
        val meta = item.itemMeta
        if (meta != null) {
            meta.persistentDataContainer.set(fluxBeamEnergyKey, PersistentDataType.INTEGER, count)
            item.itemMeta = meta
        }
    }

    private fun isBottleOfEnergy(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.EXPERIENCE_BOTTLE) return false
        val meta = item.itemMeta ?: return false

        return meta.persistentDataContainer.has(
            NamespacedKey(plugin, "energy_bottle"),
            PersistentDataType.BYTE
        )
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val cursor = event.cursor ?: return
        val current = event.currentItem ?: return

        if (isBottleOfEnergy(cursor) && isFluxGem(current)) {
            event.isCancelled = true

            if (isFluxBeamUnlocked(current)) {
                player.sendMessage("${ChatColor.GREEN}This Flux Gem already has Flux Beam unlocked!")
                return
            }

            val currentEnergy = getFluxBeamEnergy(current)

            if (currentEnergy >= ENERGY_REQUIRED_FOR_BEAM) {
                unlockFluxBeam(current)
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.8f)
                player.sendMessage("${ChatColor.AQUA}${ChatColor.BOLD}Your Flux Gem's Flux Beam has been permanently unlocked!")
                return
            }

            val newEnergy = currentEnergy + 1
            updateFluxBeamEnergy(current, newEnergy)

            cursor.amount = cursor.amount - 1

            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 2.0f)
            player.sendMessage("${ChatColor.AQUA}You added Bottled Energy to your Flux Gem! ($newEnergy/$ENERGY_REQUIRED_FOR_BEAM)")

            if (newEnergy >= ENERGY_REQUIRED_FOR_BEAM) {
                unlockFluxBeam(current)
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.8f)
                player.sendMessage("${ChatColor.AQUA}${ChatColor.BOLD}Your Flux Gem's Flux Beam has been unlocked!")
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (isFluxGem(item)) {
            event.isCancelled = true
        }
    }
    private fun canUseFluxBeam(player: Player): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastUsed = fluxBeamCooldowns.getOrDefault(player.uniqueId, 0L)

        if (currentTime - lastUsed < FLUX_BEAM_COOLDOWN) {
            val secondsLeft = ((FLUX_BEAM_COOLDOWN - (currentTime - lastUsed)) / 1000).toInt()
            player.sendMessage("${ChatColor.RED}Flux Beam ability on cooldown: ${secondsLeft}s remaining")
            return false
        }

        return true
    }
    @EventHandler(priority = EventPriority.LOW)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player

        val gemInMainHand = isFluxGem(player.inventory.itemInMainHand)
        val gemInOffHand = isFluxGem(player.inventory.itemInOffHand)

        if (!gemInMainHand && !gemInOffHand) return

        val gemItem = if (gemInMainHand) player.inventory.itemInMainHand else player.inventory.itemInOffHand

        val isGemHandInteraction =
            (event.hand == EquipmentSlot.HAND && gemInMainHand) ||
                    (event.hand == EquipmentSlot.OFF_HAND && gemInOffHand)

        checkForMultipleGems(player, gemItem)

        val lives = getGemLives(gemItem)
        val hasTier2Access = lives >= TIER_2_LIVES_REQUIREMENT

        if (player.isSneaking && (event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK)) {
            if (canUseFlashbang(player)) {
                event.isCancelled = true
                useFrostFlash(player)
            }
            return
        }

        if (!isGemHandInteraction) return

        if (player.isSneaking && (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK)) {
            if (!isFluxBeamUnlocked(gemItem)) {
                player.sendMessage("${ChatColor.RED}Your Flux Beam is locked! Add $ENERGY_REQUIRED_FOR_BEAM Bottled Energy to unlock it.")
                return
            }

            if (canUseFlashbang(player)) {
                event.isCancelled = true
                handleFluxBeam(player, gemItem)
            }
            return
        }

        if (!player.isSneaking && (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK)) {
            event.isCancelled = true
            useKineticBurst(player)
            return
        }
    }

    @EventHandler
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        val player = event.player
        val gemItem = findFluxGemInHands(player) ?: return

        if (event.hand == EquipmentSlot.OFF_HAND) return

        val lives = getGemLives(gemItem)
        val hasTier2Access = lives >= TIER_2_LIVES_REQUIREMENT

        if (player.isSneaking && event.rightClicked is Player && hasTier2Access) {
            if (canUseGround(player)) {
                event.isCancelled = true
                useGround(player, event.rightClicked as Player)
            }
        } else {
            event.isCancelled = true
            useKineticBurst(player)
        }
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (event.entity is Player) {
            val player = event.entity as Player
            val fluxGem = findFluxGemInHands(player)

            if (fluxGem != null && random.nextInt(100) < 15) {
                event.isCancelled = true
                player.world.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.5f)
                player.sendMessage("${ChatColor.AQUA}Your Flux Gem allowed you to phase through an attack!")
                return
            }
        }

        if (event.damager is org.bukkit.entity.Arrow && event.entity is LivingEntity) {
            val arrow = event.damager as org.bukkit.entity.Arrow
            if (arrow.shooter is Player) {
                val shooter = arrow.shooter as Player
                val fluxGem = findFluxGemInHands(shooter)

                if (fluxGem != null && random.nextInt(100) < 20) {
                    val target = event.entity as LivingEntity
                    target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 2))
                    target.addPotionEffect(PotionEffect(PotionEffectType.NAUSEA, 100, 0))
                    shooter.sendMessage("${ChatColor.AQUA}Your arrow shocked your target!")
                }
            }
        }
    }

    @EventHandler
    fun onPlayerDamage(event: EntityDamageEvent) {
        if (event.entity is Player) {
            val player = event.entity as Player

            if (frozenPlayers.containsKey(player.uniqueId)) {
                player.sendMessage("${ChatColor.BLUE}You are frozen and can't move!")
            }
        }
    }

    @EventHandler
    fun onPlayerKill(event: org.bukkit.event.entity.PlayerDeathEvent) {
        val player = event.entity

        event.drops.removeIf { isFluxGem(it) }

        val fluxGem = findFluxGemInHands(player)
        if (fluxGem != null) {
            val lives = getGemLives(fluxGem)
            if (lives <= 0) {
                player.sendMessage("${ChatColor.RED}Your Flux Gem has been destroyed!")
                player.banPlayer("${ChatColor.RED}You have lost all your lives! Your gem has been destroyed!")
            } else {
                val updatedGem = fluxGem.clone()
                updateGemLives(updatedGem, lives - 1)
                plugin.gemsToBeSaved[player.uniqueId] = updatedGem
                player.sendMessage("${ChatColor.AQUA}Your Flux Gem lost a life! (${lives - 1}/$MAX_LIVES lives)")
            }
        }

        val killer = event.entity.killer
        if (killer != null) {
            val killerGem = findFluxGemInHands(killer)
            if (killerGem != null) {
                val lives = getGemLives(killerGem)
                if (lives < MAX_LIVES) {
                    updateGemLives(killerGem, lives + 1)
                    killer.sendMessage("${ChatColor.AQUA}Your Flux Gem gained a life! (${lives + 1}/$MAX_LIVES lives)")
                } else {
                    killer.sendMessage("${ChatColor.YELLOW}Your Flux Gem is already at maximum lives!")
                }
            }
        }
    }
    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val uuid = player.uniqueId

        if (plugin.gemsToBeSaved.containsKey(uuid)) {
            val gem = plugin.gemsToBeSaved.remove(uuid)

            plugin.logger.info("Restoring Flux Gem for ${player.name}")

            object : BukkitRunnable() {
                override fun run() {
                    if (player.isOnline && gem != null) {
                        if (findFluxGemInHands(player) == null && !playerHasFluxGemInInventory(player)) {
                            player.inventory.addItem(gem)
                            plugin.logger.info("Successfully restored Flux Gem for ${player.name}")
                        }
                    }
                }
            }.runTaskLater(plugin, 10L)
        }
    }

    private fun playerHasFluxGemInInventory(player: Player): Boolean {
        for (item in player.inventory.contents) {
            if (item != null && isFluxGem(item)) {
                return true
            }
        }
        return false
    }

    private fun startFlowStateChecker() {
        object : BukkitRunnable() {
            override fun run() {
                for (player in Bukkit.getOnlinePlayers()) {
                    val fluxGem = findFluxGemInHands(player)
                    if (fluxGem != null) {
                        player.removePotionEffect(PotionEffectType.WEAKNESS)
                        player.removePotionEffect(PotionEffectType.SLOWNESS)
                        player.removePotionEffect(PotionEffectType.HUNGER)

                        if (player.location.block.type == Material.WATER) {
                            player.addPotionEffect(PotionEffect(PotionEffectType.WATER_BREATHING, 40, 0, false, false))
                            player.addPotionEffect(PotionEffect(PotionEffectType.DOLPHINS_GRACE, 40, 1, false, false))
                        }

                        val flowLevel = flowStateMap.getOrDefault(player.uniqueId, 0)

                        if (flowLevel > 0) {
                            val effectLevel = when {
                                flowLevel >= 30 -> 2
                                flowLevel >= 15 -> 1
                                else -> 0
                            }

                            if (effectLevel > 0) {
                                player.addPotionEffect(PotionEffect(PotionEffectType.HASTE, 40, effectLevel, true, true))
                                player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 40, effectLevel, true, true))

                                if (System.currentTimeMillis() % 5000 < 20) {
                                    player.sendActionBar("${ChatColor.AQUA}Flow State: ${ChatColor.WHITE}$flowLevel/${FLOW_STATE_MAX}")
                                }
                            }

                            flowStateMap[player.uniqueId] = flowLevel - FLOW_STATE_DECAY_RATE
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L)
    }

    private fun startFrozenPlayersChecker() {
        object : BukkitRunnable() {
            override fun run() {
                val currentTime = System.currentTimeMillis()
                val playersToUnfreeze = mutableListOf<UUID>()

                frozenPlayers.forEach { (uuid, endTime) ->
                    if (currentTime >= endTime) {
                        playersToUnfreeze.add(uuid)
                        val player = Bukkit.getPlayer(uuid)
                        player?.sendMessage("${ChatColor.AQUA}You are no longer frozen!")
                    }
                }

                playersToUnfreeze.forEach { frozenPlayers.remove(it) }
            }
        }.runTaskTimer(plugin, 5L, 5L)
    }

    private fun canUseFlashbang(player: Player): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastUsed = flashbangCooldowns.getOrDefault(player.uniqueId, 0L)

        if (currentTime - lastUsed < FLASHBANG_COOLDOWN) {
            val secondsLeft = ((FLASHBANG_COOLDOWN - (currentTime - lastUsed)) / 1000).toInt()
            player.sendMessage("${ChatColor.RED}Frost Flash ability on cooldown: ${secondsLeft}s remaining")
            return false
        }

        return true
    }

    private fun canUseKineticBurst(player: Player): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastUsed = kineticBurstCooldowns.getOrDefault(player.uniqueId, 0L)

        if (currentTime - lastUsed < KINETIC_BURST_COOLDOWN) {
            val secondsLeft = ((KINETIC_BURST_COOLDOWN - (currentTime - lastUsed)) / 1000).toInt()
            player.sendMessage("${ChatColor.RED}Kinetic Burst ability on cooldown: ${secondsLeft}s remaining")
            return false
        }

        return true
    }

    private fun useFrostFlash(player: Player) {
        player.world.playSound(player.location, Sound.ENTITY_GUARDIAN_ATTACK, 1.5f, 2.0f)
        flashbangCooldowns[player.uniqueId] = System.currentTimeMillis()

        player.world.spawnParticle(
            org.bukkit.Particle.FLASH,
            player.location,
            50, 5.0, 5.0, 5.0, 0.2
        )

        player.world.spawnParticle(
            org.bukkit.Particle.END_ROD,
            player.location,
            100, 4.0, 2.0, 4.0, 0.1
        )

        player.world.spawnParticle(
            org.bukkit.Particle.GLOW,
            player.location,
            80, 4.0, 2.0, 4.0, 0.05
        )

        player.world.spawnParticle(
            org.bukkit.Particle.BLOCK,
            player.location,
            80, 5.0, 1.5, 5.0, 0.1,
            Material.ICE.createBlockData()
        )

        player.world.spawnParticle(
            org.bukkit.Particle.SNOWFLAKE,
            player.location,
            60, 5.0, 2.0, 5.0, 0.05
        )

        val nearbyEntities = player.getNearbyEntities(10.0, 10.0, 10.0)
        for (entity in nearbyEntities) {
            if (entity is LivingEntity) {
                entity.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 100, 0))

                if (entity is Player) {
                    entity.velocity = Vector(0, 0, 0)
                    entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 100))
                    entity.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, 60, 128))
                    frozenPlayers[entity.uniqueId] = System.currentTimeMillis() + FREEZE_DURATION
                }
            }
        }

        player.sendMessage("${ChatColor.AQUA}You unleashed a powerful freezing flash of light!")

        increaseFlowState(player)
    }

    private fun useKineticBurst(player: Player) {
        if (!canUseKineticBurst(player)) {
            return
        }

        kineticBurstCooldowns[player.uniqueId] = System.currentTimeMillis()

        player.world.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.6f)
        player.world.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f)

        player.world.spawnParticle(
            org.bukkit.Particle.END_ROD,
            player.location,
            50, 0.5, 0.1, 0.5, 0.1
        )

        val direction = player.location.direction.normalize()
        val launchVector = if (direction.y > 0.7) {
            Vector(direction.x * 0.8, 3.5, direction.z * 0.8)
        } else {
            Vector(direction.x * 2.0, 1.8, direction.z * 2.0)
        }
        player.velocity = launchVector
        player.velocity = launchVector

        player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 200, 6, false, true))

        player.sendMessage("${ChatColor.AQUA}You activated Kinetic Burst and launched into the air!")

        object : BukkitRunnable() {
            var ticks = 0
            override fun run() {
                ticks++
                if (ticks > 60 || !player.isOnline) {
                    cancel()
                    return
                }

                player.world.spawnParticle(
                    org.bukkit.Particle.CLOUD,
                    player.location.add(0.0, -0.5, 0.0),
                    5, 0.2, 0.0, 0.2, 0.01
                )
            }
        }.runTaskTimer(plugin, 1L, 1L)

        increaseFlowState(player)
    }

    private fun canUseGround(player: Player): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastUsed = groundCooldowns.getOrDefault(player.uniqueId, 0L)

        if (currentTime - lastUsed < GROUND_COOLDOWN) {
            val secondsLeft = ((GROUND_COOLDOWN - (currentTime - lastUsed)) / 1000).toInt()
            player.sendMessage("${ChatColor.RED}Ground ability on cooldown: ${secondsLeft}s remaining")
            return false
        }

        return true
    }


    private fun useGround(player: Player, target: Player) {
        player.world.playSound(player.location, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f)
        target.velocity = Vector(0, 0, 0)

        target.world.spawnParticle(
            org.bukkit.Particle.BLOCK,
            target.location,
            40, 0.5, 0.5, 0.5, 0.1,
            Material.ICE.createBlockData()
        )

        frozenPlayers[target.uniqueId] = System.currentTimeMillis() + FREEZE_DURATION
        groundCooldowns[player.uniqueId] = System.currentTimeMillis()

        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 100))
        target.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, 60, 128))
        player.sendMessage("${ChatColor.AQUA}You froze ${target.name} in place!")

        increaseFlowState(player)
    }

    private fun handleFluxBeam(player: Player, gemItem: ItemStack) {
        if (!canUseFlashbang(player)) {
            return
        }
        if (!canUseFluxBeam(player)) {
            return
        }

        if (fluxBeamCharging.containsKey(player.uniqueId)) {
            val charge = fluxBeamCharging[player.uniqueId] ?: 0
            if (charge > 5) {
                fireFluxBeam(player, gemItem, Math.min(charge, 100))
            }
            fluxBeamCharging.remove(player.uniqueId)
            return
        }

        player.sendMessage("${ChatColor.AQUA}Charging Flux Beam...")
        player.world.playSound(player.location, Sound.BLOCK_BEACON_AMBIENT, 1.0f, 1.0f)
        fluxBeamCharging[player.uniqueId] = 1

        object : BukkitRunnable() {
            var tickCount = 0
            var charge = 1

            override fun run() {
                tickCount++

                if (!player.isOnline || !player.isSneaking || findFluxGemInHands(player) == null) {
                    if (charge > 10) {
                        fireFluxBeam(player, gemItem, Math.min(charge, 100))
                    } else {
                        player.sendMessage("${ChatColor.RED}Flux Beam charge interrupted!")
                    }
                    fluxBeamCharging.remove(player.uniqueId)
                    cancel()
                    return
                }

                if (tickCount % 3 == 0) {
                    charge += 5
                    fluxBeamCharging[player.uniqueId] = charge

                    val chargePercent = Math.min(charge, 100)
                    if (chargePercent % 10 == 0) {
                        player.sendActionBar("${ChatColor.AQUA}Flux Beam: ${ChatColor.WHITE}${chargePercent}%")
                        player.world.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HARP, 0.5f, 0.5f + (chargePercent / 100f))
                    }

                    player.world.spawnParticle(
                        org.bukkit.Particle.DRIPPING_WATER,
                        player.eyeLocation,
                        10, 0.3, 0.3, 0.3, 0.1
                    )
                }

                if (charge >= 100) {
                    fireFluxBeam(player, gemItem, 100)
                    fluxBeamCharging.remove(player.uniqueId)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 2L, 2L)
    }

    private fun fireFluxBeam(player: Player, gemItem: ItemStack, charge: Int) {
        updateFluxBeamEnergy(gemItem, 0)
        player.sendMessage("${ChatColor.AQUA}Your Flux Gem's energy has been depleted!")
        fluxBeamCooldowns[player.uniqueId] = System.currentTimeMillis()

        if (isFluxGem(player.inventory.itemInMainHand)) {
            player.inventory.setItemInMainHand(gemItem)
        } else if (isFluxGem(player.inventory.itemInOffHand)) {
            player.inventory.setItemInOffHand(gemItem)
        }

        val baseDamage = 4.0
        val damageMultiplier = 1.0 + (charge / 50.0)
        val damage = baseDamage * damageMultiplier

        player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 2.0f)
        player.world.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 2.0f)

        player.sendMessage("${ChatColor.AQUA}You fired a Flux Beam with ${ChatColor.WHITE}${charge}%${ChatColor.AQUA} power!")

        val range = 30.0
        val entities = player.getNearbyEntities(range, range, range)
        val direction = player.location.direction

        val beamLength = 30
        val startLoc = player.eyeLocation

        for (i in 1..beamLength) {
            val loc = startLoc.clone().add(direction.clone().multiply(i.toDouble()))
            player.world.spawnParticle(
                org.bukkit.Particle.UNDERWATER,
                loc,
                10, 0.1, 0.1, 0.1, 0.0
            )
            player.world.spawnParticle(
                org.bukkit.Particle.GLOW,
                loc,
                5, 0.1, 0.1, 0.1, 0.0
            )
        }

        var hitEntity: Entity? = null
        var closestDistance = Double.MAX_VALUE

        for (entity in entities) {
            if (entity != player && entity is LivingEntity) {
                val eLoc = entity.eyeLocation
                val playerLoc = player.eyeLocation

                val toEntity = eLoc.toVector().subtract(playerLoc.toVector())
                val dot = toEntity.normalize().dot(direction)

                if (dot > 0.8) {
                    val distance = playerLoc.distance(eLoc)
                    if (distance < closestDistance) {
                        closestDistance = distance
                        hitEntity = entity
                    }
                }
            }
        }

        if (hitEntity != null && hitEntity is LivingEntity) {
            val target = hitEntity as LivingEntity
            target.damage(damage, player)

            val knockbackPower = charge / 25.0
            val knockbackDirection = direction.clone().multiply(knockbackPower)
            target.velocity = target.velocity.add(knockbackDirection)
        }

        increaseFlowState(player)
    }

    private fun increaseFlowState(player: Player) {
        val currentTime = System.currentTimeMillis()
        val lastActionTime = lastActionMap.getOrDefault(player.uniqueId, 0L)
        val timeSinceLastAction = currentTime - lastActionTime

        val currentFlow = flowStateMap.getOrDefault(player.uniqueId, 0)
        var flowIncrease = 10

        if (timeSinceLastAction < 2000) {
            flowIncrease += 5
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.0f)
        }

        val newFlow = Math.min(currentFlow + flowIncrease, FLOW_STATE_MAX)
        flowStateMap[player.uniqueId] = newFlow
        lastActionMap[player.uniqueId] = currentTime

        if (newFlow >= 30 && currentFlow < 30) {
            player.sendMessage("${ChatColor.AQUA}You've reached high Flow State!")
        }
    }

    private fun checkForMultipleGems(player: Player, currentGem: ItemStack) {
        var fluxGemCount = 0
        for (item in player.inventory.contents) {
            if (item != null && isFluxGem(item) && item != currentGem &&
                item != player.inventory.itemInMainHand && item != player.inventory.itemInOffHand) {
                fluxGemCount++
                player.inventory.remove(item)
            }
        }

        if (fluxGemCount > 0) {
            player.sendMessage("${ChatColor.RED}The Flux Gem's energy destabilized other gems in your inventory!")
        }
    }

    fun isFluxGem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.CYAN_CANDLE) return false

        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(fluxGemKey, PersistentDataType.BYTE)
    }

    private fun findFluxGemInHands(player: Player): ItemStack? {
        if (isFluxGem(player.inventory.itemInMainHand)) {
            return player.inventory.itemInMainHand
        }
        if (isFluxGem(player.inventory.itemInOffHand)) {
            return player.inventory.itemInOffHand
        }
        return null
    }

    private fun getGemLives(item: ItemStack): Int {
        val meta = item.itemMeta ?: return DEFAULT_LIVES
        return meta.persistentDataContainer.getOrDefault(plugin.gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
    }

    private fun updateGemLives(item: ItemStack, lives: Int) {
        val meta = item.itemMeta
        if (meta != null) {
            meta.persistentDataContainer.set(plugin.gemLivesKey, PersistentDataType.INTEGER, lives)

            val lore = meta.lore
            if (lore != null) {
                val newLore = lore.toMutableList()
                for (i in newLore.indices) {
                    if (newLore[i].contains("Lives:")) {
                        newLore[i] = "${ChatColor.BLUE}Lives: ${ChatColor.WHITE}$lives/$MAX_LIVES"
                        break
                    }
                }
                meta.lore = newLore
            }

            item.itemMeta = meta
        }
    }

    private fun getTargetedEntity(player: Player, maxDistance: Double): Entity? {
        val direction = player.eyeLocation.direction
        val startPos = player.eyeLocation

        val entities = player.getNearbyEntities(maxDistance, maxDistance, maxDistance)
        var target: Entity? = null
        var closestDistance = Double.MAX_VALUE

        for (entity in entities) {
            if (entity != player) {
                val entPos = entity.location
                val toEntity = entPos.clone().subtract(startPos).toVector()
                val dot = toEntity.normalize().dot(direction)

                if (dot > 0.9) {
                    val distance = startPos.distance(entPos)
                    if (distance < closestDistance && distance <= maxDistance) {
                        closestDistance = distance
                        target = entity
                    }
                }
            }
        }

        return target
    }

}