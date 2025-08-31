package io.papermc.blisssmp

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.HashMap
import java.util.UUID
import kotlin.collections.remove
import kotlin.compareTo
import kotlin.text.set

class SpeedGem(private val plugin: BlissSMPPlugin) : Listener {

    private val speedGemKey = NamespacedKey(plugin, "speed_gem")
    private val gemLivesKey = NamespacedKey(plugin, "gem_lives")
    private val anyGemKey = NamespacedKey(plugin, "any_gem")
    private val stormEnergyKey = NamespacedKey(plugin, "storm_energy")
    private val stormUnlockedKey = NamespacedKey(plugin, "storm_unlocked")
    private val ENERGY_REQUIRED = 10
    private val slothCooldowns = HashMap<UUID, Long>()
    private val stormCooldowns = HashMap<UUID, Long>()
    private val activeStorms = HashMap<UUID, Long>()
    private val gemsToBeSaved = HashMap<UUID, ItemStack>()

    private val SLOTH_COOLDOWN = 30 * 1000L
    private val STORM_COOLDOWN = 240 * 1000L
    private val STORM_DURATION = 60 * 1000L
    private val DEFAULT_LIVES = 5
    private val MAX_LIVES = 10

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun createSpeedGem(): ItemStack {
        val speedGem = ItemStack(Material.GLOWSTONE_DUST)
        val meta = speedGem.itemMeta

        if (meta != null) {
            meta.setDisplayName("${ChatColor.YELLOW}${ChatColor.BOLD}Speed Gem")
            meta.lore = listOf(
                "${ChatColor.GRAY}A mystical gem that enhances its wielder's speed.",
                "",
                "${ChatColor.YELLOW}Lives: ${ChatColor.WHITE}$DEFAULT_LIVES/$MAX_LIVES",
                "${ChatColor.AQUA}Speed Storm: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ENERGY_REQUIRED Energy)",
                "",
                "${ChatColor.GOLD}Passives:",
                "${ChatColor.WHITE}• PRISTINE: Speed 3, Dolphin's Grace",
                "${ChatColor.WHITE}• Immune to Soul Sand slowness",
                "${ChatColor.WHITE}• Auto enchants tools to Efficiency 5",
                "",
                "${ChatColor.GOLD}Powers:",
                "${ChatColor.WHITE}• SLOTH'S SEDATIVE: Inflict slowness (Right-Click)",
                "${ChatColor.WHITE}• SPEED STORM: Create a thunderstorm (Shift+Right-Click)",
                "",
                "${ChatColor.YELLOW}• Kill players to gain lives (Max: ${MAX_LIVES})",
                "${ChatColor.YELLOW}• Lose a life when you die"
            )

            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES)
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true)
            meta.persistentDataContainer.set(speedGemKey, PersistentDataType.BYTE, 1)
            meta.persistentDataContainer.set(anyGemKey, PersistentDataType.BYTE, 1)
            meta.persistentDataContainer.set(gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
            meta.persistentDataContainer.set(stormEnergyKey, PersistentDataType.INTEGER, 0)
            meta.persistentDataContainer.set(stormUnlockedKey, PersistentDataType.BYTE, 0)
            speedGem.itemMeta = meta
        }

        return speedGem
    }

    fun isSpeedGem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.GLOWSTONE_DUST) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(speedGemKey, PersistentDataType.BYTE)
    }
    private fun isStormUnlocked(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.getOrDefault(stormUnlockedKey, PersistentDataType.BYTE, 0) == 1.toByte()
    }

    private fun getStormEnergy(item: ItemStack): Int {
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(stormEnergyKey, PersistentDataType.INTEGER, 0)
    }

    private fun setStormEnergy(item: ItemStack, count: Int) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(stormEnergyKey, PersistentDataType.INTEGER, count)
        item.itemMeta = meta
    }

    private fun unlockStorm(item: ItemStack) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(stormUnlockedKey, PersistentDataType.BYTE, 1)
        val lore = meta.lore?.toMutableList() ?: return
        for (i in lore.indices) {
            if (lore[i].contains("Speed Storm:")) {
                lore[i] = "${ChatColor.AQUA}Speed Storm: ${ChatColor.GREEN}Unlocked"
            }
        }
        meta.lore = lore
        item.itemMeta = meta
    }

    private fun updateSpeedGemLore(item: ItemStack) {
        val meta = item.itemMeta ?: return
        val lore = meta.lore?.toMutableList() ?: return
        val energy = getStormEnergy(item)
        val unlocked = isStormUnlocked(item)
        for (i in lore.indices) {
            if (lore[i].contains("Speed Storm:")) {
                lore[i] = if (unlocked) {
                    "${ChatColor.AQUA}Speed Storm: ${ChatColor.GREEN}Unlocked"
                } else {
                    "${ChatColor.AQUA}Speed Storm: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ENERGY_REQUIRED Energy) ${ChatColor.YELLOW}[$energy/$ENERGY_REQUIRED]"
                }
            }
        }
        meta.lore = lore
        item.itemMeta = meta
    }

    private fun getGemFromPlayer(player: Player): ItemStack? {
        val mainHandItem = player.inventory.itemInMainHand
        val offHandItem = player.inventory.itemInOffHand

        return when {
            isSpeedGem(mainHandItem) -> mainHandItem
            isSpeedGem(offHandItem) -> offHandItem
            else -> null
        }
    }

    private fun getGemLives(item: ItemStack): Int {
        if (!isSpeedGem(item)) return 0
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
    }

    private fun setGemLives(item: ItemStack, lives: Int): ItemStack {
        if (!isSpeedGem(item)) return item

        val meta = item.itemMeta ?: return item
        val adjustedLives = lives.coerceIn(0, MAX_LIVES)
        meta.persistentDataContainer.set(gemLivesKey, PersistentDataType.INTEGER, adjustedLives)

        val lore = meta.lore ?: mutableListOf()
        if (lore.isNotEmpty() && lore.size > 2) {
            lore[2] = "${ChatColor.YELLOW}Lives: ${ChatColor.WHITE}$adjustedLives/$MAX_LIVES"
        }
        meta.lore = lore

        item.itemMeta = meta
        return item
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = if (event.hand == EquipmentSlot.HAND) player.inventory.itemInMainHand else player.inventory.itemInOffHand
    
        if (!isSpeedGem(item)) return
    
        val offHand = player.inventory.itemInOffHand
        val mainHand = player.inventory.itemInMainHand

        if ((isSpeedGem(mainHand) && isBottledOfEnergy(offHand)) ||
            (isSpeedGem(offHand) && isBottledOfEnergy(mainHand))) {
    
            val gemItem = if (isSpeedGem(mainHand)) mainHand else offHand
            val energyItem = if (isBottledOfEnergy(mainHand)) mainHand else offHand
    
            event.isCancelled = true
    
            if (isStormUnlocked(gemItem)) {
                player.sendMessage("${ChatColor.YELLOW}Speed Storm is already unlocked!")
            } else {
                val currentEnergy = getStormEnergy(gemItem)
                if (currentEnergy < ENERGY_REQUIRED) {
                    setStormEnergy(gemItem, currentEnergy + 1)
                    energyItem.amount -= 1
                    player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
                    player.sendMessage("${ChatColor.AQUA}Added energy to Speed Gem! (${currentEnergy + 1}/$ENERGY_REQUIRED)")
    
                    if (currentEnergy + 1 >= ENERGY_REQUIRED) {
                        unlockStorm(gemItem)
                        player.sendMessage("${ChatColor.GREEN}${ChatColor.BOLD}SPEED STORM UNLOCKED!")
                        player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.5f)
                    }
                    updateSpeedGemLore(gemItem)
                }
            }
            return
        }

        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            event.isCancelled = true
    
            if (player.isSneaking) {
                if (!isStormUnlocked(item)) {
                    player.sendMessage("${ChatColor.RED}Speed Storm is locked! Add $ENERGY_REQUIRED Bottled Of Energy to unlock.")
                    return
                }
                activateSpeedStorm(player)
            } else {
                activateSlothSedative(player)
            }
        }
    }
    

    @EventHandler
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        val item = if (event.hand == EquipmentSlot.HAND) player.inventory.itemInMainHand else player.inventory.itemInOffHand

        if (!isSpeedGem(item)) return

        val target = event.rightClicked
        if (target is LivingEntity) {
            if (player.isSneaking) {
                activateSpeedStorm(player, target)
            } else {
                applySlothEffects(player, target)
            }
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        activeStorms.remove(player.uniqueId)

        event.drops.removeIf { isSpeedGem(it) }

        val gem = findGemInInventory(player)
        if (gem != null) {
            val lives = getGemLives(gem)
            if (lives > 0) {
                val updatedLives = lives - 1
                val updatedGem = gem.clone()
                setGemLives(updatedGem, updatedLives)
                gemsToBeSaved[player.uniqueId] = updatedGem
                player.sendMessage("${ChatColor.YELLOW}Your Speed Gem lost a life! ($updatedLives/$MAX_LIVES lives remaining)")
            } else {
                player.sendMessage("${ChatColor.RED}Your Speed Gem has been destroyed! (0 lives remaining)")
                player.banPlayer("${ChatColor.RED}You have lost all your lives! Your gem has been destroyed!")
            }
        }

        val killer = player.killer
        if (killer != null) {
            val killerGem = findGemInInventory(killer)
            if (killerGem != null) {
                val lives = getGemLives(killerGem)
                if (lives < MAX_LIVES) {
                    setGemLives(killerGem, lives + 1)
                    killer.sendMessage("${ChatColor.YELLOW}Your Speed Gem gained a life! (${lives + 1}/$MAX_LIVES lives)")
                } else {
                    killer.sendMessage("${ChatColor.YELLOW}Your Speed Gem is already at maximum lives! ($MAX_LIVES/$MAX_LIVES lives)")
                }
            }
        }
    }

    private fun findGemInInventory(player: Player): ItemStack? {
        if (isSpeedGem(player.inventory.itemInOffHand)) {
            return player.inventory.itemInOffHand
        }

        for (item in player.inventory.contents) {
            if (item != null && isSpeedGem(item)) {
                return item
            }
        }

        return null
    }

    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val savedGem = gemsToBeSaved[player.uniqueId]

        if (savedGem != null) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (player.isOnline) {
                    player.inventory.addItem(savedGem)
                    player.sendMessage("${ChatColor.YELLOW}Your Speed Gem has returned to you!")
                    gemsToBeSaved.remove(player.uniqueId)
                }
            }, 5L)
        }
    }

    @EventHandler
    fun onInventoryClick(event: org.bukkit.event.inventory.InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val clickedItem = event.currentItem
        val cursorItem = event.cursor

        if (isSpeedGem(clickedItem) && isBottledOfEnergy(cursorItem)) {
            event.isCancelled = true
            if (isStormUnlocked(clickedItem!!)) {
                player.sendMessage("${ChatColor.YELLOW}Speed Storm is already unlocked!")
                return
            }
            val currentEnergy = getStormEnergy(clickedItem)
            if (currentEnergy < ENERGY_REQUIRED) {
                setStormEnergy(clickedItem, currentEnergy + 1)
                cursorItem!!.amount -= 1
                val newEnergy = currentEnergy + 1
                player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
                player.sendMessage("${ChatColor.AQUA}Added energy to Speed Gem! (${newEnergy}/$ENERGY_REQUIRED)")
                if (newEnergy >= ENERGY_REQUIRED) {
                    unlockStorm(clickedItem)
                    player.sendMessage("${ChatColor.GREEN}${ChatColor.BOLD}SPEED STORM UNLOCKED!")
                    player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.5f)
                }
                updateSpeedGemLore(clickedItem)
            }
            return
        }

        if ((isSpeedGem(clickedItem) || isSpeedGem(cursorItem))) {
            var gemCount = 0

            val movingGemOut = isSpeedGem(clickedItem) && event.clickedInventory == player.inventory

            if (isSpeedGem(player.inventory.itemInOffHand) && !(movingGemOut && event.slot == player.inventory.heldItemSlot + 40)) {
                gemCount++
            }

            for (i in player.inventory.contents.indices) {
                val item = player.inventory.contents[i]
                if (item != null && item.hasItemMeta() &&
                    item.itemMeta!!.persistentDataContainer.has(anyGemKey, PersistentDataType.BYTE) &&
                    !(movingGemOut && event.slot == i)) {
                    gemCount++
                }
            }

            val wouldHaveMultipleGems =
                (isSpeedGem(clickedItem) && gemCount > 0) ||
                (isSpeedGem(cursorItem) && gemCount > 0) ||
                (isSpeedGem(clickedItem) && isSpeedGem(cursorItem))

            if (wouldHaveMultipleGems) {
                event.isCancelled = true
                player.sendMessage("${ChatColor.RED}You can only carry one gem at a time!")
            }
        }
    }
    
    private fun isBottledOfEnergy(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.EXPERIENCE_BOTTLE) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(
            NamespacedKey(plugin, "energy_bottle"),
            PersistentDataType.BYTE
        )
    }
    @EventHandler
    fun onPlayerPickupItem(event: org.bukkit.event.entity.EntityPickupItemEvent) {
        if (event.entity !is Player) return
        val player = event.entity as Player
        val item = event.item.itemStack

        if (isSpeedGem(item)) {
            if (isSpeedGem(player.inventory.itemInOffHand)) {
                event.isCancelled = true
                player.sendMessage("${ChatColor.RED}You already have a gem in your inventory! You cannot carry more than one gem at a time.")
                return
            }

            for (invItem in player.inventory.contents) {
                if (invItem != null && invItem.hasItemMeta() &&
                    invItem.itemMeta!!.persistentDataContainer.has(anyGemKey, PersistentDataType.BYTE)) {
                    event.isCancelled = true
                    player.sendMessage("${ChatColor.RED}You already have a gem in your inventory! You cannot carry more than one gem at a time.")
                    break
                }
            }
        }
    }

    private fun activateSlothSedative(player: Player) {
        val uuid = player.uniqueId
        val currentTime = System.currentTimeMillis()

        if (slothCooldowns.containsKey(uuid) && currentTime < slothCooldowns[uuid]!!) {
            val remainingSeconds = ((slothCooldowns[uuid]!! - currentTime) / 1000).toInt()
            player.sendMessage("${ChatColor.RED}Sloth's Sedative is on cooldown for $remainingSeconds more seconds.")
            return
        }

        val targets = player.getNearbyEntities(8.0, 8.0, 8.0)
            .filterIsInstance<LivingEntity>()
            .filter { it != player }

        if (targets.isEmpty()) {
            player.sendMessage("${ChatColor.YELLOW}No targets found within range!")
            return
        }

        var affectedCount = 0
        for (target in targets) {
            if (target is LivingEntity) {
                applySlothEffects(player, target)
                affectedCount++
            }
        }

        player.world.playSound(player.location, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 0.5f)
        player.sendMessage("${ChatColor.YELLOW}You've cast Sloth's Sedative, affecting $affectedCount entities!")
        slothCooldowns[uuid] = currentTime + SLOTH_COOLDOWN
    }

    private fun applySlothEffects(player: Player, target: LivingEntity) {
        if (target is Player) {
            target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40 * 20, 3))
            target.addPotionEffect(PotionEffect(PotionEffectType.MINING_FATIGUE, 40 * 20, 2))

            target.removePotionEffect(PotionEffectType.SPEED)
            target.removePotionEffect(PotionEffectType.DOLPHINS_GRACE)

            target.sendMessage("${ChatColor.RED}You've been afflicted with Sloth's Sedative!")
        } else {
            target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 30 * 20, 1))
            target.addPotionEffect(PotionEffect(PotionEffectType.MINING_FATIGUE, 30 * 20, 2))
        }
    }

    private fun activateSpeedStorm(player: Player, targetEntity: Entity? = null) {
        val uuid = player.uniqueId
        val currentTime = System.currentTimeMillis()

        if (stormCooldowns.containsKey(uuid) && currentTime < stormCooldowns[uuid]!!) {
            val remainingSeconds = ((stormCooldowns[uuid]!! - currentTime) / 1000).toInt()
            player.sendMessage("${ChatColor.RED}Speed Storm is on cooldown for $remainingSeconds more seconds.")
            return
        }


        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 60 * 20, 2))

        if (targetEntity != null) {
            strikeLightningAtTarget(player, targetEntity, 15)
        } else {
            val target = player.getTargetBlock(null, 100)
            if (target != null && target.type != Material.AIR) {
                strikeLightningAtBlock(player, target, 15)
            } else {
                scheduleStormLightning(player)
            }
        }

        activeStorms[uuid] = currentTime + STORM_DURATION

        stormCooldowns[uuid] = currentTime + STORM_COOLDOWN

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline) {
                activeStorms.remove(uuid)
            }
        }, 60 * 20L)
    }

    private fun strikeLightningAtTarget(player: Player, target: Entity, strikes: Int) {
        var strikesRemaining = strikes

        plugin.server.scheduler.runTaskTimer(plugin, object : Runnable {
            override fun run() {
                if (strikesRemaining <= 0 || !player.isOnline || !target.isValid) {
                    val tasks = plugin.server.scheduler.pendingTasks
                    for (task in tasks) {
                        if (task.owner == plugin && task.isSync) {
                            task.cancel()
                            break
                        }
                    }
                    return
                }

                val location = target.location
                player.world.strikeLightning(location)

                if (target is LivingEntity && target != player) {
                    target.damage(3.0, player)
                    if (strikesRemaining % 5 == 0) {
                        target.world.playSound(target.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.5f)
                    }
                }

                strikesRemaining--
            }
        }, 5L, 10L)
    }

    private fun strikeLightningAtBlock(player: Player, block: Block, strikes: Int) {
        var strikesRemaining = strikes

        plugin.server.scheduler.runTaskTimer(plugin, object : Runnable {
            override fun run() {
                if (strikesRemaining <= 0 || !player.isOnline) {
                    val tasks = plugin.server.scheduler.pendingTasks
                    for (task in tasks) {
                        if (task.owner == plugin && task.isSync) {
                            task.cancel()
                            break
                        }
                    }
                    return
                }

                val location = block.location.add(0.5, 0.0, 0.5)
                player.world.strikeLightning(location)

                val nearbyEntities = location.world.getNearbyEntities(location, 3.0, 3.0, 3.0)
                for (entity in nearbyEntities) {
                    if (entity is LivingEntity && entity != player) {
                        entity.damage(2.0, player)
                    }
                }

                strikesRemaining--
            }
        }, 5L, 10L)
    }

    private fun scheduleStormLightning(player: Player) {
        val uuid = player.uniqueId

        plugin.server.scheduler.runTaskTimer(plugin, object : Runnable {
            override fun run() {
                if (!activeStorms.containsKey(uuid) || !player.isOnline) {
                    val tasks = plugin.server.scheduler.pendingTasks
                    for (task in tasks) {
                        if (task.owner == plugin && task.isSync) {
                            task.cancel()
                            break
                        }
                    }
                    return
                }

                val loc = player.location
                val world = loc.world ?: return

                val random = java.util.Random()

                for (i in 0 until 3) {
                    val offsetX = random.nextInt(31) - 15
                    val offsetZ = random.nextInt(31) - 15

                    val strikeX = loc.x + offsetX
                    val strikeZ = loc.z + offsetZ

                    val highestY = world.getHighestBlockYAt(strikeX.toInt(), strikeZ.toInt())

                    val strikeLocation = org.bukkit.Location(world, strikeX, highestY.toDouble(), strikeZ)

                    world.strikeLightning(strikeLocation)
                }
            }
        }, 20L, 20L)
    }

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = when {
            event.damager is Player -> event.damager as Player
            else -> return
        }

        val hasSpeedGem = hasSpeedGem(damager)
        val inActiveStorm = activeStorms.containsKey(damager.uniqueId)

        if (hasSpeedGem && inActiveStorm) {
            val damage = event.damage * 1.5
            event.damage = damage
        }

        val victim = when {
            event.entity is Player -> event.entity as Player
            else -> return
        }

        if (victim.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            val slowLevel = victim.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier ?: 0
            val multiplier = if (slowLevel >= 3) 0.5 else 0.8

            event.damage = event.damage * multiplier
        }
    }

    @EventHandler
    fun onPlayerJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
        val player = event.player
        checkAndApplyPassives(player)
    }

    @EventHandler
    fun onItemPickup(event: org.bukkit.event.entity.EntityPickupItemEvent) {
        if (event.entity is Player) {
            val player = event.entity as Player
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                checkAndApplyPassives(player)
            }, 1L)
        }
    }

    @EventHandler
    fun onItemHeld(event: org.bukkit.event.player.PlayerItemHeldEvent) {
        val player = event.player
        if (!hasSpeedGem(player)) return

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val heldItem = player.inventory.getItem(event.newSlot) ?: return@Runnable

            if (isTool(heldItem)) {
                val meta = heldItem.itemMeta
                if (meta != null && !meta.hasEnchant(org.bukkit.enchantments.Enchantment.EFFICIENCY) ||
                    meta.getEnchantLevel(org.bukkit.enchantments.Enchantment.EFFICIENCY) < 5) {
                    meta.addEnchant(org.bukkit.enchantments.Enchantment.EFFICIENCY, 5, true)
                    heldItem.itemMeta = meta
                    player.sendMessage("${ChatColor.YELLOW}Your tool has been infused with speed!")
                }
            }
        }, 1L)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerMove(event: org.bukkit.event.player.PlayerMoveEvent) {
        val player = event.player
        if (!hasSpeedGem(player)) return

        val blockBelow = player.location.block.getRelative(org.bukkit.block.BlockFace.DOWN)
        if (blockBelow.type == Material.SOUL_SAND || blockBelow.type == Material.SOUL_SOIL) {
            player.walkSpeed = 0.2f

            if (player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
                val effect = player.getPotionEffect(PotionEffectType.SLOWNESS)
                if (effect != null && effect.amplifier == 0 && effect.duration < 20) {
                    player.removePotionEffect(PotionEffectType.SLOWNESS)
                }
            }

            if (!player.hasPotionEffect(PotionEffectType.SPEED) ||
                (player.getPotionEffect(PotionEffectType.SPEED)?.amplifier ?: -1) < 2
            ) {
                player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 10, 2, false, false, false))
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerVelocity(event: org.bukkit.event.player.PlayerVelocityEvent) {
        val player = event.player
        if (!hasSpeedGem(player)) return

        val blockBelow = player.location.block.getRelative(org.bukkit.block.BlockFace.DOWN)
        if (blockBelow.type == Material.SOUL_SAND || blockBelow.type == Material.SOUL_SOIL) {
            val velocity = event.velocity
            event.velocity = velocity.multiply(1.25)
        }
    }

    private fun hasSpeedGem(player: Player): Boolean {
        return isSpeedGem(player.inventory.itemInMainHand) ||
                isSpeedGem(player.inventory.itemInOffHand) ||
                player.inventory.any { isSpeedGem(it) }
    }

    private fun checkAndApplyPassives(player: Player) {
        val hasGem = hasSpeedGem(player)

        var gemCount = 0

        if (isSpeedGem(player.inventory.itemInOffHand)) {
            gemCount++
        }

        gemCount += player.inventory.contents
            .filterNotNull()
            .count { it.hasItemMeta() && it.itemMeta!!.persistentDataContainer.has(anyGemKey, PersistentDataType.BYTE) }

        if (gemCount > 1) {
            var foundFirst = false

            if (isSpeedGem(player.inventory.itemInOffHand)) {
                if (foundFirst) {
                    val item = player.inventory.itemInOffHand
                    player.inventory.setItemInOffHand(null)
                    player.world.dropItem(player.location, item)
                } else {
                    foundFirst = true
                }
            }

            for (i in 0 until player.inventory.size) {
                val item = player.inventory.getItem(i)
                if (item != null && item.hasItemMeta() &&
                    item.itemMeta!!.persistentDataContainer.has(anyGemKey, PersistentDataType.BYTE)) {
                    if (foundFirst) {
                        player.inventory.setItem(i, null)
                        player.world.dropItem(player.location, item)
                    } else {
                        foundFirst = true
                    }
                }
            }
        }

        if (hasGem) {
            player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 2, false, false, true))
            player.addPotionEffect(PotionEffect(PotionEffectType.DOLPHINS_GRACE, Integer.MAX_VALUE, 0, false, false, true))
        } else {
            if (player.hasPotionEffect(PotionEffectType.SPEED)) {
                val effect = player.getPotionEffect(PotionEffectType.SPEED)
                if (effect != null && effect.duration > 1000 && effect.amplifier == 2) {
                    player.removePotionEffect(PotionEffectType.SPEED)
                }
            }
            if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
                player.removePotionEffect(PotionEffectType.DOLPHINS_GRACE)
            }
        }
    }

    private fun isTool(item: ItemStack): Boolean {
        return when (item.type) {
            Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.IRON_PICKAXE,
            Material.GOLDEN_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE,
            Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE,
            Material.GOLDEN_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE,
            Material.WOODEN_SHOVEL, Material.STONE_SHOVEL, Material.IRON_SHOVEL,
            Material.GOLDEN_SHOVEL, Material.DIAMOND_SHOVEL, Material.NETHERITE_SHOVEL,
            Material.WOODEN_HOE, Material.STONE_HOE, Material.IRON_HOE,
            Material.GOLDEN_HOE, Material.DIAMOND_HOE, Material.NETHERITE_HOE -> true
            else -> false
        }
    }
}