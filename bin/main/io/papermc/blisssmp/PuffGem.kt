package io.papermc.blisssmp

import com.destroystokyo.paper.event.player.PlayerJumpEvent
import org.bukkit.*
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.*
import org.bukkit.Sound
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.HashMap
import java.util.UUID
import kotlin.collections.remove
import kotlin.compareTo
import kotlin.text.compareTo
import kotlin.text.set

class PuffGem(private val plugin: BlissSMPPlugin) : Listener {
    private val puffGemKey: NamespacedKey = NamespacedKey(plugin, "puff_gem")
    private val gemLivesKey: NamespacedKey = NamespacedKey(plugin, "gem_lives")
    private val anyGemKey: NamespacedKey = NamespacedKey(plugin, "any_gem")


    private val gemCooldowns = mutableMapOf<UUID, MutableMap<String, Long>>()
    private val gemsToBeSaved = HashMap<UUID, ItemStack>()

    private val lastJumpTime = mutableMapOf<UUID, Long>()
    private val canDoubleJump = mutableMapOf<UUID, Boolean>()
    private val playersWithDoubleJumpEnabled = mutableSetOf<UUID>()
    private val GROUND_CHECK_DELAY = 5L
    private val puffGemEnergyKey: NamespacedKey = NamespacedKey(plugin, "puff_gem_energy")
    private val puffGemUnlockedKey: NamespacedKey = NamespacedKey(plugin, "puff_gem_unlocked")
    private val ENERGY_REQUIRED = 10

    private val DEFAULT_LIVES = 5
    private val MAX_LIVES = 10
    private val DASH_COOLDOWN = 10000L
    private val BREEZY_BASH_COOLDOWN = 30000L
    private val maxDashDistance = 15.0
    private val DOUBLE_JUMP_COOLDOWN = 1000L
    private val doubleJumpCooldowns = mutableMapOf<UUID, Long>()


    private val DOUBLE_JUMP_WINDOW = 500L
    private val DOUBLE_JUMP_MULTIPLIER = 2.0

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        startItemEnchantmentTask()
    }

    fun createPuffGem(): ItemStack {
        val puffGem = ItemStack(Material.SUGAR)
        val meta = puffGem.itemMeta ?: return puffGem

        meta.setDisplayName("${ChatColor.WHITE}${ChatColor.BOLD}Puff Gem")

        meta.lore = listOf(
            "${ChatColor.GRAY}A mystical gem with the power of sugar.",
            "",
            "${ChatColor.WHITE}Lives: ${ChatColor.WHITE}$DEFAULT_LIVES/${MAX_LIVES}",
            "",
            "${ChatColor.LIGHT_PURPLE}Passives:",
            "${ChatColor.WHITE}• PRISTINE: No fall damage",
            "${ChatColor.WHITE}• Auto-enchants bows to Power V and Punch II",
            "${ChatColor.WHITE}• Auto-enchants boots to Feather Falling IV",
            "${ChatColor.WHITE}• DOUBLE JUMP: Press jump twice rapidly to jump higher",
            "",
            "${ChatColor.LIGHT_PURPLE}Powers:",
            "${ChatColor.WHITE}• BREEZY BASH: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ENERGY_REQUIRED Energy)",
            "${ChatColor.WHITE}• DASH: Dash forward, dealing 2 hearts of damage (Right-Click)",
            "",
            "${ChatColor.YELLOW}• Kill players to gain lives (Max: ${MAX_LIVES})",
            "${ChatColor.YELLOW}• Lose a life when you die",
        )

        meta.isUnbreakable = true
        meta.addEnchant(Enchantment.UNBREAKING, 1, true)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE)

        meta.persistentDataContainer.set(puffGemKey, PersistentDataType.BYTE, 1)
        meta.persistentDataContainer.set(anyGemKey, PersistentDataType.BYTE, 1)
        meta.persistentDataContainer.set(gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
        meta.persistentDataContainer.set(puffGemEnergyKey, PersistentDataType.INTEGER, 0)
        meta.persistentDataContainer.set(puffGemUnlockedKey, PersistentDataType.BYTE, 0)

        puffGem.itemMeta = meta
        return puffGem
    }
    private fun updatePuffGemLore(item: ItemStack) {
        val meta = item.itemMeta ?: return
        val lore = meta.lore?.toMutableList() ?: return
        val unlocked = isPuffGemUnlocked(item)
        val energy = getPuffGemEnergy(item)
        for (i in lore.indices) {
            if (lore[i].contains("BREEZY BASH:")) {
                if (unlocked) {
                    lore[i] = "${ChatColor.WHITE}• BREEZY BASH: ${ChatColor.GREEN}Unlocked"
                } else {
                    lore[i] = "${ChatColor.WHITE}• BREEZY BASH: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ENERGY_REQUIRED Energy) ${ChatColor.YELLOW}[$energy/$ENERGY_REQUIRED]"
                }
            }
        }
        meta.lore = lore
        item.itemMeta = meta
    }
    fun isPuffGem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.SUGAR) return false

        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(puffGemKey, PersistentDataType.BYTE)
    }
    private fun isPuffGemUnlocked(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.getOrDefault(puffGemUnlockedKey, PersistentDataType.BYTE, 0) == 1.toByte()
    }

    private fun getPuffGemEnergy(item: ItemStack): Int {
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(puffGemEnergyKey, PersistentDataType.INTEGER, 0)
    }

    private fun setPuffGemEnergy(item: ItemStack, amount: Int) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(puffGemEnergyKey, PersistentDataType.INTEGER, amount)
        item.itemMeta = meta
    }

    private fun unlockPuffGem(item: ItemStack) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(puffGemUnlockedKey, PersistentDataType.BYTE, 1)

        val lore = meta.lore
        if (lore != null) {
            val newLore = lore.toMutableList()
            for (i in newLore.indices) {
                if (newLore[i].contains("Puff Gem:")) {
                    newLore[i] = "${ChatColor.AQUA}Puff Gem: ${ChatColor.GREEN}Unlocked"
                    break
                }
            }
            meta.lore = newLore
        }

        item.itemMeta = meta
    }

    private fun isBottledOfEnergy(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.EXPERIENCE_BOTTLE) return false
        val meta = item.itemMeta ?: return false

        return meta.persistentDataContainer.has(
            NamespacedKey(plugin, "energy_bottle"),
            PersistentDataType.BYTE
        )
    }
    fun isAnyGem(item: ItemStack?): Boolean {
        if (item == null) return false

        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(anyGemKey, PersistentDataType.BYTE) ||
                meta.persistentDataContainer.has(puffGemKey, PersistentDataType.BYTE) ||
                meta.displayName?.contains("Gem") == true
    }

    fun hasAnyGemInInventory(player: Player): Boolean {
        for (item in player.inventory.contents) {
            if (item != null && isAnyGem(item)) {
                return true
            }
        }
        return false
    }

    private fun getGemLives(item: ItemStack): Int {
        if (!isPuffGem(item)) return 0
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
    }

    private fun setGemLives(item: ItemStack, lives: Int): ItemStack {
        if (!isPuffGem(item)) return item

        val meta = item.itemMeta ?: return item
        val adjustedLives = lives.coerceIn(0, MAX_LIVES)
        meta.persistentDataContainer.set(gemLivesKey, PersistentDataType.INTEGER, adjustedLives)

        val lore = meta.lore ?: mutableListOf()
        if (lore.isNotEmpty() && lore.size > 2) {
            lore[2] = "${ChatColor.WHITE}Lives: ${ChatColor.WHITE}$adjustedLives/$MAX_LIVES"
        }
        meta.lore = lore

        item.itemMeta = meta
        return item
    }

    private fun hasPuffGemInInventory(player: Player): Boolean {
        for (item in player.inventory.contents) {
            if (item != null && isPuffGem(item)) {
                return true
            }
        }
        return false
    }

    private fun isGemOnCooldown(player: Player, ability: String): Boolean {
        val playerCooldowns = gemCooldowns.getOrPut(player.uniqueId) { mutableMapOf() }
        val lastUsage = playerCooldowns[ability] ?: 0L
        val currentTime = System.currentTimeMillis()

        return when (ability) {
            "dash" -> currentTime - lastUsage < DASH_COOLDOWN
            "breezyBash" -> currentTime - lastUsage < BREEZY_BASH_COOLDOWN
            else -> false
        }
    }

    private fun setGemCooldown(player: Player, ability: String) {
        val playerCooldowns = gemCooldowns.getOrPut(player.uniqueId) { mutableMapOf() }
        playerCooldowns[ability] = System.currentTimeMillis()
    }

    private fun getRemainingCooldown(player: Player, ability: String): Long {
        val playerCooldowns = gemCooldowns.getOrPut(player.uniqueId) { mutableMapOf() }
        val lastUsage = playerCooldowns[ability] ?: return 0L
        val cooldownDuration = when (ability) {
            "dash" -> DASH_COOLDOWN
            "breezyBash" -> BREEZY_BASH_COOLDOWN
            else -> 0L
        }

        return (cooldownDuration - (System.currentTimeMillis() - lastUsage)) / 1000
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        if (hasPuffGemInInventory(player)) {
            for (item in player.inventory.contents) {
                if (item == null) continue

                if (item.type == Material.BOW && !item.containsEnchantment(Enchantment.POWER)) {
                    item.addUnsafeEnchantment(Enchantment.POWER, 5)
                    item.addUnsafeEnchantment(Enchantment.PUNCH, 2)
                } else if (item.type.name.endsWith("BOOTS") && !item.containsEnchantment(Enchantment.FEATHER_FALLING)) {
                    item.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, 4)
                }
            }
        }
    }

    @EventHandler
    fun onItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player

        if (!hasPuffGemInInventory(player)) return

        val newItem = player.inventory.getItem(event.newSlot) ?: return

        if (newItem.type == Material.BOW && !newItem.containsEnchantment(Enchantment.POWER)) {
            newItem.addUnsafeEnchantment(Enchantment.POWER, 5)
            newItem.addUnsafeEnchantment(Enchantment.PUNCH, 2)
            player.sendMessage("${ChatColor.LIGHT_PURPLE}Your bow has been enchanted by the Puff Gem!")
        } else if (newItem.type.name.endsWith("BOOTS") && !newItem.containsEnchantment(Enchantment.FEATHER_FALLING)) {
            newItem.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, 4)
            player.sendMessage("${ChatColor.LIGHT_PURPLE}Your boots have been enchanted by the Puff Gem!")
        }
    }

    @EventHandler
    fun onItemReceive(event: PlayerAttemptPickupItemEvent) {
        val player = event.player

        if (!hasPuffGemInInventory(player)) return

        val item = event.item.itemStack

        if (item.type == Material.BOW && !item.containsEnchantment(Enchantment.POWER)) {
            item.addUnsafeEnchantment(Enchantment.POWER, 5)
            item.addUnsafeEnchantment(Enchantment.PUNCH, 2)
        } else if (item.type.name.endsWith("BOOTS") && !item.containsEnchantment(Enchantment.FEATHER_FALLING)) {
            item.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, 4)
        }
    }

    private fun disableFlightSafely(player: Player) {
        if (player.allowFlight && player.gameMode != org.bukkit.GameMode.CREATIVE) {
            player.allowFlight = false
            playersWithDoubleJumpEnabled.remove(player.uniqueId)
        }
    }
    @EventHandler
    fun onItemDrop(event: PlayerDropItemEvent) {
        val player = event.player
        val droppedItem = event.itemDrop.itemStack

        if (isPuffGem(droppedItem)) {
            object : BukkitRunnable() {
                override fun run() {
                    if (!hasPuffGemInInventory(player)) {
                        disableFlightSafely(player)
                    }
                }
            }.runTaskLater(plugin, 1L)
        }
    }

    @EventHandler
    fun onCraftItem(event: org.bukkit.event.inventory.CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return

        if (!hasPuffGemInInventory(player)) return

        val result = event.inventory.result ?: return

        if (result.type == Material.BOW && !result.containsEnchantment(Enchantment.POWER)) {
            result.addUnsafeEnchantment(Enchantment.POWER, 5)
            result.addUnsafeEnchantment(Enchantment.PUNCH, 2)
        } else if (result.type.name.endsWith("BOOTS") && !result.containsEnchantment(Enchantment.FEATHER_FALLING)) {
            result.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, 4)
        }
    }

    private fun startItemEnchantmentTask() {
        object : BukkitRunnable() {
            override fun run() {
                for (player in plugin.server.onlinePlayers) {
                    if (hasPuffGemInInventory(player)) {
                        for (item in player.inventory.contents) {
                            if (item == null) continue

                            if (item.type == Material.BOW && !item.containsEnchantment(Enchantment.POWER)) {
                                item.addUnsafeEnchantment(Enchantment.POWER, 5)
                                item.addUnsafeEnchantment(Enchantment.PUNCH, 2)
                            } else if (item.type.name.endsWith("BOOTS") && !item.containsEnchantment(Enchantment.FEATHER_FALLING)) {
                                item.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, 4)
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 100L, 100L)
    }

    @EventHandler
    fun onPlayerPickupItem(event: PlayerPickupItemEvent) {
        val player = event.player
        val item = event.item.itemStack

        if (isAnyGem(item) && hasAnyGemInInventory(player)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val clickedItem = event.currentItem
        val cursorItem = event.cursor

        if (isAnyGem(clickedItem) || isAnyGem(cursorItem)) {
            val gemCount = player.inventory.contents.count { item ->
                item != null && isAnyGem(item) && item != clickedItem
            }

            val wouldHaveMultipleGems = when {
                isAnyGem(clickedItem) && gemCount > 0 -> true
                isAnyGem(cursorItem) && gemCount > 0 -> true
                isAnyGem(clickedItem) && isAnyGem(cursorItem) -> true
                else -> false
            }

            if (wouldHaveMultipleGems) {
                event.isCancelled = true
                player.sendMessage("${ChatColor.RED}You can only carry one gem at a time!")
                return
            }
        }

        if (clickedItem != null && cursorItem != null &&
            isPuffGem(clickedItem) && isBottledOfEnergy(cursorItem)) {
            event.isCancelled = true

            if (isPuffGemUnlocked(clickedItem)) {
                player.sendMessage("${ChatColor.LIGHT_PURPLE}Breezy Bash is already unlocked!")
                return
            }

            val currentEnergy = getPuffGemEnergy(clickedItem)
            if (currentEnergy < ENERGY_REQUIRED && cursorItem.amount > 0) {
                setPuffGemEnergy(clickedItem, currentEnergy + 1)
                cursorItem.amount--

                val newEnergy = currentEnergy + 1
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f)
                player.sendMessage("${ChatColor.LIGHT_PURPLE}Added energy to Puff Gem! ($newEnergy/$ENERGY_REQUIRED)")

                if (newEnergy >= ENERGY_REQUIRED) {
                    unlockPuffGem(clickedItem)
                    player.sendMessage("${ChatColor.GREEN}${ChatColor.BOLD}BREEZY BASH UNLOCKED!")
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
                }
                updatePuffGemLore(clickedItem)
            }
            return
        }

        if (!hasPuffGemInInventory(player)) return

        val item = clickedItem ?: return

        try {
            when {
                item.type == Material.BOW && !item.containsEnchantment(Enchantment.POWER) -> {
                    item.addUnsafeEnchantment(Enchantment.POWER, 5)
                    item.addUnsafeEnchantment(Enchantment.PUNCH, 2)
                    player.sendMessage("${ChatColor.LIGHT_PURPLE}Your bow has been enchanted by the Puff Gem!")
                }
                item.type.name.endsWith("BOOTS") && !item.containsEnchantment(Enchantment.FEATHER_FALLING) -> {
                    item.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, 4)
                    player.sendMessage("${ChatColor.LIGHT_PURPLE}Your boots have been enchanted by the Puff Gem!")
                }
            }

            if (isPuffGem(clickedItem)) {
                object : BukkitRunnable() {
                    override fun run() {
                        if (!hasPuffGemInInventory(player)) {
                            disableFlightSafely(player)
                        }
                    }
                }.runTaskLater(plugin, 1L)
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error in Puff Gem enchanting: ${e.message}")
        }
    }



    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return

        if (!isPuffGem(item)) return

        event.isCancelled = true

        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        try {
            when {
                player.isSneaking -> {
                    if (!isPuffGemUnlocked(item)) {
                        player.sendMessage("${ChatColor.RED}Breezy Bash is locked! Add $ENERGY_REQUIRED Bottled Of Energy to unlock.")
                        return
                    }
                    if (isGemOnCooldown(player, "breezyBash")) {
                        val timeLeft = getRemainingCooldown(player, "breezyBash")
                        player.sendMessage("${ChatColor.RED}Breezy Bash is on cooldown for $timeLeft more seconds!")
                        return
                    }
                    executeBreezyBash(player)
                    setGemCooldown(player, "breezyBash")
                }
                else -> {
                    if (isGemOnCooldown(player, "dash")) {
                        val timeLeft = getRemainingCooldown(player, "dash")
                        player.sendMessage("${ChatColor.RED}Dash is on cooldown for $timeLeft more seconds!")
                        return
                    }
                    executeDash(player)
                    setGemCooldown(player, "dash")
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error in Puff Gem interaction: ${e.message}")
            player.sendMessage("${ChatColor.RED}An error occurred while using the Puff Gem.")
        }
    }

    private fun scheduleGroundCheck(player: Player) {
        object : BukkitRunnable() {
            override fun run() {
                if (player.isOnGround && player.gameMode != org.bukkit.GameMode.CREATIVE) {
                    disableFlightSafely(player)
                } else if (player.isOnline) {
                    scheduleGroundCheck(player)
                }
            }
        }.runTaskLater(plugin, 1L)
    }
    @EventHandler
    fun onPlayerJumpEvent(event: PlayerJumpEvent) {
        val player = event.player

        if (!hasPuffGemInInventory(player)) return

        lastJumpTime[player.uniqueId] = System.currentTimeMillis()
        canDoubleJump[player.uniqueId] = true

        if (!player.allowFlight && player.gameMode != org.bukkit.GameMode.CREATIVE) {
            player.allowFlight = true
            playersWithDoubleJumpEnabled.add(player.uniqueId)
        }

        object : BukkitRunnable() {
            override fun run() {
                val timeSinceJump = System.currentTimeMillis() - (lastJumpTime[player.uniqueId] ?: 0L)
                if (timeSinceJump >= DOUBLE_JUMP_WINDOW) {
                    canDoubleJump[player.uniqueId] = false
                    if (player.gameMode != GameMode.CREATIVE && player.isOnGround) {
                        disableFlightSafely(player)
                    } else {
                        scheduleGroundCheck(player)
                    }
                }
            }
        }.runTaskLater(plugin, (DOUBLE_JUMP_WINDOW / 50) + 1L)
    }

    @EventHandler
    fun onPlayerToggleFlight(event: PlayerToggleFlightEvent) {
        val player = event.player
        if (player.gameMode == GameMode.CREATIVE) return
        if (!hasPuffGemInInventory(player)) return
        if (!event.isFlying) return

        val currentTime = System.currentTimeMillis()
        val canJump = canDoubleJump.getOrDefault(player.uniqueId, false)
        val lastCooldown = doubleJumpCooldowns[player.uniqueId] ?: 0L
        val cooldownRemaining = DOUBLE_JUMP_COOLDOWN - (currentTime - lastCooldown)

        if (cooldownRemaining > 0) {
            event.isCancelled = true
            player.sendMessage("${ChatColor.RED}Double Jump is on cooldown for ${cooldownRemaining / 1000.0} seconds!")
            disableFlightSafely(player)
            return
        }

        if (canJump) {
            val lastJump = lastJumpTime[player.uniqueId] ?: 0L
            val withinWindow = currentTime - lastJump <= DOUBLE_JUMP_WINDOW

            if (withinWindow) {
                event.isCancelled = true
                val velocity = player.location.direction.multiply(1.0).setY(0.42 * DOUBLE_JUMP_MULTIPLIER)
                player.velocity = velocity
                canDoubleJump[player.uniqueId] = false
                doubleJumpCooldowns[player.uniqueId] = currentTime

                player.world.playSound(player.location, Sound.ENTITY_GHAST_SHOOT, 1.0f, 1.0f)
                player.world.spawnParticle(Particle.CLOUD, player.location.add(0.0, 0.1, 0.0), 20, 0.5, 0.1, 0.5, 0.1)

                object : BukkitRunnable() {
                    override fun run() {
                        disableFlightSafely(player)
                        scheduleGroundCheck(player)
                    }
                }.runTaskLater(plugin, 3L)

                object : BukkitRunnable() {
                    override fun run() {
                        player.fallDistance = 0f
                    }
                }.runTaskLater(plugin, 5L)
            } else {
                event.isCancelled = true
                disableFlightSafely(player)
            }
        } else {
            event.isCancelled = true
            disableFlightSafely(player)
        }
    }


    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val uuid = player.uniqueId

        if (!hasPuffGemInInventory(player)) {
            if (playersWithDoubleJumpEnabled.contains(uuid)) {
                disableFlightSafely(player)
            }
            return
        }

        if (player.isOnGround && player.gameMode != org.bukkit.GameMode.CREATIVE) {
            canDoubleJump[uuid] = true
            if (!player.allowFlight) {
                player.allowFlight = true
                playersWithDoubleJumpEnabled.add(uuid)
            }
        } else if (!player.isOnGround) {
            if (player.velocity.y > 0) {
                lastJumpTime[uuid] = System.currentTimeMillis()
            }
        }
    }

    @EventHandler
    fun onPlayerFall(event: PlayerMoveEvent) {
        val player = event.player
        val uuid = player.uniqueId

        if (!hasPuffGemInInventory(player)) return

        if (player.gameMode == org.bukkit.GameMode.CREATIVE) return

        if (!player.isOnGround && player.velocity.y < -0.1) {
            val timeSinceLastJump = System.currentTimeMillis() - (lastJumpTime[uuid] ?: 0L)

            if (timeSinceLastJump > 500) {
                canDoubleJump[uuid] = false
            }
        }
    }
    @EventHandler
    fun onFallDamage(event: EntityDamageEvent) {
        if (event.entity is Player && event.cause == EntityDamageEvent.DamageCause.FALL) {
            val player = event.entity as Player
            if (hasPuffGemInInventory(player)) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val uuid = player.uniqueId

        gemCooldowns.remove(uuid)
        lastJumpTime.remove(uuid)
        canDoubleJump.remove(uuid)
        playersWithDoubleJumpEnabled.remove(uuid)
        doubleJumpCooldowns.remove(uuid)
        if (player.allowFlight && player.gameMode != org.bukkit.GameMode.CREATIVE) {
            player.allowFlight = false
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        var foundGem = false

        // First, remove all puff gems from drops
        event.drops.removeIf { isPuffGem(it) }

        // Clear any existing saved gems for this player
        gemsToBeSaved.remove(player.uniqueId)

        // Remove all puff gems from player's inventory and save one with reduced lives
        for (item in player.inventory.contents) {
            if (item != null && isPuffGem(item) && !foundGem) {
                foundGem = true
                val gem = item.clone()
                val lives = getGemLives(gem)

                item.amount = 0

                if (lives > 0) {
                    val updatedGem = setGemLives(gem, lives - 1)
                    gemsToBeSaved[player.uniqueId] = updatedGem
                    player.sendMessage("${ChatColor.LIGHT_PURPLE}Your Puff Gem lost a life! (${lives - 1}/$MAX_LIVES lives remaining)")
                } else {
                    player.sendMessage("${ChatColor.RED}Your Puff Gem has been destroyed! (0 lives remaining)")
                    player.banPlayer("${ChatColor.RED}You have lost all your lives! Your gem has been destroyed!")
                }
            } else if (item != null && isPuffGem(item)) {
                item.amount = 0
            }
        }

        val killer = player.killer
        if (killer != null) {
            for (item in killer.inventory.contents) {
                if (item != null && isPuffGem(item)) {
                    val lives = getGemLives(item)
                    if (lives < MAX_LIVES) {
                        setGemLives(item, lives + 1)
                        killer.sendMessage("${ChatColor.GREEN}Your Puff Gem gained a life! (${lives + 1}/$MAX_LIVES lives)")
                    } else {
                        killer.sendMessage("${ChatColor.YELLOW}Your Puff Gem is already at maximum lives! ($MAX_LIVES/$MAX_LIVES lives)")
                    }
                    break
                }
            }
        }
    }


    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val savedGem = gemsToBeSaved[player.uniqueId]

        if (savedGem != null) {
            object : BukkitRunnable() {
                override fun run() {
                    if (player.isOnline) {
                        player.inventory.addItem(savedGem)
                        player.sendMessage("${ChatColor.LIGHT_PURPLE}Your Puff Gem has returned to you!")
                        gemsToBeSaved.remove(player.uniqueId)
                    }
                }
            }.runTaskLater(plugin, 5L)
        }
    }

    private fun executeDash(player: Player) {
        val direction = player.location.direction.normalize().multiply(2.5)
        player.velocity = direction
        player.world.playSound(player.location, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.0f)
        player.sendMessage("${ChatColor.LIGHT_PURPLE}DASH!")

        val initialLocation = player.location.clone()

        object : BukkitRunnable() {
            var distance = 0.0
            override fun run() {
                if (distance >= maxDashDistance || player.velocity.lengthSquared() < 0.1) {
                    cancel()
                    return
                }

                for (entity in player.getNearbyEntities(1.5, 1.5, 1.5)) {
                    if (entity is LivingEntity && entity != player) {
                        entity.damage(4.0, player)
                        val knockbackDir = entity.location.toVector().subtract(player.location.toVector()).normalize().multiply(1.2)
                        knockbackDir.y += 0.3
                        entity.velocity = knockbackDir

                        entity.world.spawnParticle(
                            org.bukkit.Particle.CRIT,
                            entity.location.add(0.0, 1.0, 0.0),
                            10,
                            0.5,
                            0.5,
                            0.5,
                            0.1
                        )
                    }
                }

                distance = player.location.distance(initialLocation)
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun executeBreezyBash(player: Player) {
        player.sendMessage("${ChatColor.LIGHT_PURPLE}BREEZY BASH!")
        player.world.playSound(player.location, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 1.2f)

        player.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, 40, 10))
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 80, 0))

        object : BukkitRunnable() {
            override fun run() {
                player.removePotionEffect(PotionEffectType.LEVITATION)
                player.removePotionEffect(PotionEffectType.SLOW_FALLING)
                player.velocity = Vector(0, -3, 0)
                player.fallDistance = 0f

                object : BukkitRunnable() {
                    override fun run() {
                        if (player.isOnGround) {
                            createImpactEffect(player)
                            cancel()
                        }
                    }
                }.runTaskTimer(plugin, 5L, 1L)
            }
        }.runTaskLater(plugin, 40L)
    }

    private fun createImpactEffect(player: Player) {
        player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f)

        for (entity in player.getNearbyEntities(8.0, 3.0, 8.0)) {
            if (entity != player && entity is LivingEntity) {
                val direction = entity.location.toVector().subtract(player.location.toVector())
                if (direction.lengthSquared() > 0) {
                    direction.normalize().multiply(2.5).setY(0.5)
                    entity.velocity = direction
                    entity.damage(6.0, player)

                    if (entity is Player) {
                        entity.sendMessage("${ChatColor.LIGHT_PURPLE}You were hit by ${player.name}'s Breezy Bash!")
                    }
                }
            }
        }

        player.world.spawnParticle(org.bukkit.Particle.EXPLOSION, player.location, 5)
        player.world.spawnParticle(org.bukkit.Particle.CLOUD, player.location, 40, 3.0, 0.5, 3.0, 0.1)
        player.world.spawnParticle(org.bukkit.Particle.SWEEP_ATTACK, player.location, 20, 3.0, 0.2, 3.0, 0.1)
    }
}