package io.papermc.blisssmp

import org.bukkit.*
import org.bukkit.entity.Entity
import org.bukkit.entity.Fireball
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import java.util.*

class FireGem(private val plugin: BlissSMPPlugin) : Listener {

    private lateinit var fireGemKey: NamespacedKey
    private lateinit var playerLivesKey: NamespacedKey
    private lateinit var fireballUnlockKey: NamespacedKey
    private lateinit var energyCountKey: NamespacedKey
    private val chargingPlayers = HashMap<UUID, Int>()
    private val fireballCooldowns = HashMap<UUID, Long>()
    private val campfireCooldowns = HashMap<UUID, Long>()
    private val FIRE_GEM_CAMPFIRE_METADATA = "fire_gem_campfire"

    private val FIREBALL_COOLDOWN = 10000L
    private val CAMPFIRE_COOLDOWN = 60000L
    private val MAX_CHARGE = 100
    private val CHARGE_RATE = 5
    private val CHARGE_RATE_BOOST = 10
    private val MAX_LIVES = 10
    private val DEFAULT_LIVES = 5
    private val FIRE_THORNS_CHANCE = 0.5
    private val ENERGY_REQUIRED = 10
    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        fireGemKey = NamespacedKey(plugin, "fire_gem")
        playerLivesKey = NamespacedKey(plugin, "player_lives")
        fireballUnlockKey = NamespacedKey(plugin, "fireball_unlocked")
        energyCountKey = NamespacedKey(plugin, "energy_count")
        startChargingTask()
    }

    fun createFireGem(): ItemStack {
        val fireGem = ItemStack(Material.BLAZE_POWDER)
        val meta = fireGem.itemMeta
        if (meta != null) {
            meta.setDisplayName("${ChatColor.GOLD}${ChatColor.BOLD}Fire Gem")
            meta.lore = listOf(
                "${ChatColor.GRAY}A blazing gem that burns with eternal flame.",
                "",
                "${ChatColor.GOLD}Lives: ${ChatColor.WHITE}$DEFAULT_LIVES/${MAX_LIVES}",
                "${ChatColor.GOLD}Fireball: ${ChatColor.RED}Locked ${ChatColor.WHITE}(Requires $ENERGY_REQUIRED Energy)",
                "",
                "${ChatColor.GOLD}Passives:",
                "${ChatColor.WHITE}• Auto Smelting: Automatically smelts gold, iron, copper, and ancient debris",
                "${ChatColor.WHITE}• Fire Thorns: Burns attackers when damaged",
                "${ChatColor.WHITE}• Auto Enchant: Enchants bows with Flame and swords with Fire Aspect",
                "",
                "${ChatColor.GOLD}Powers:",
                "${ChatColor.WHITE}• FIREBALL: Charge and release destructive fire (Right-Click)",
                "${ChatColor.WHITE}• COZY CAMPFIRE: Create a healing campfire (Shift+Right-Click)",
                "",
                "${ChatColor.YELLOW}• Kill players to gain lives (Max: ${MAX_LIVES})",
                "${ChatColor.YELLOW}• Drag Bottled Of Energy onto gem to unlock Fireball",
                "${ChatColor.RED}• 0 Lives = Server Ban"
            )
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES)
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true)
            meta.persistentDataContainer.set(fireGemKey, PersistentDataType.STRING, "fire_gem")
            meta.persistentDataContainer.set(playerLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)

            meta.persistentDataContainer.set(energyCountKey, PersistentDataType.INTEGER, 0)

            meta.persistentDataContainer.set(fireballUnlockKey, PersistentDataType.INTEGER, 0)
        }

        fireGem.itemMeta = meta
        return fireGem
    }

    fun isFireGem(item: ItemStack?): Boolean {
        if (item == null) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(fireGemKey, PersistentDataType.STRING)
    }

    private fun findFireGemInHands(player: Player): ItemStack? {
        return when {
            isFireGem(player.inventory.itemInMainHand) -> player.inventory.itemInMainHand
            isFireGem(player.inventory.itemInOffHand) -> player.inventory.itemInOffHand
            else -> null
        }
    }

    private fun getGemLives(item: ItemStack): Int {
        val meta = item.itemMeta ?: return DEFAULT_LIVES
        return meta.persistentDataContainer.getOrDefault(playerLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
    }

    private fun setGemLives(item: ItemStack, lives: Int) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(playerLivesKey, PersistentDataType.INTEGER, lives)

        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        val updatedLore = mutableListOf<String>()

        for (line in lore) {
            if (line.contains("Lives:", ignoreCase = true)) {
                updatedLore.add("${ChatColor.GOLD}Lives: ${ChatColor.WHITE}$lives/$MAX_LIVES")
            } else {
                updatedLore.add(line)
            }
        }

        meta.lore = updatedLore
        item.itemMeta = meta
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDamageByFireball(event: EntityDamageByEntityEvent) {
        if (event.damager !is Fireball) return
        val fireball = event.damager as Fireball

        if (fireball.shooter !is Player) return
        val shooter = fireball.shooter as Player

        if (!hasFireGemInInventory(shooter)) return

        val chargePercentage = (fireball.yield * 20).coerceIn(5.0F, 100.0F)

        val damage = (2.0 + ((24.0 - 2.0) * (chargePercentage / 100.0)))

        event.isCancelled = true

        if (event.entity is LivingEntity) {
            val victim = event.entity as LivingEntity
            victim.damage(damage)

            victim.world.spawnParticle(
                Particle.FLAME,
                victim.location.add(0.0, 1.0, 0.0),
                20,
                0.5, 0.5, 0.5,
                0.1
            )
        }
    }


    fun isBottledOfEnergy(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.EXPERIENCE_BOTTLE) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(
            NamespacedKey(plugin, "energy_bottle"),
            PersistentDataType.BYTE
        )
    }
    @EventHandler
    fun onInventoryClick(event: org.bukkit.event.inventory.InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val clickedItem = event.currentItem
        val cursorItem = event.cursor

        if (isFireGem(clickedItem) && isBottledOfEnergy(cursorItem)) {
            event.isCancelled = true

            if (isFireballUnlocked(clickedItem!!)) {
                player.sendMessage("${ChatColor.YELLOW}Fireball is already unlocked!")
                return
            }

            val currentEnergy = getEnergyCount(clickedItem)
            if (currentEnergy < ENERGY_REQUIRED) {
                setEnergyCount(clickedItem, currentEnergy + 1)
                cursorItem!!.amount -= 1

                val newEnergy = currentEnergy + 1
                val progress = newEnergy.toFloat() / ENERGY_REQUIRED

                player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, progress)

                player.world.spawnParticle(
                    org.bukkit.Particle.FLAME,
                    player.location.add(0.0, 1.0, 0.0),
                    10,
                    0.3, 0.3, 0.3,
                    0.05
                )

                player.sendMessage("${ChatColor.GOLD}Added energy to Fire Gem! (${newEnergy}/$ENERGY_REQUIRED)")

                if (newEnergy >= ENERGY_REQUIRED) {
                    unlockFireball(clickedItem)
                    player.sendMessage("${ChatColor.GREEN}${ChatColor.BOLD}FIREBALL UNLOCKED!")

                    player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.5f)
                    player.world.spawnParticle(
                        org.bukkit.Particle.LAVA,
                        player.location.add(0.0, 1.0, 0.0),
                        20,
                        0.5, 0.5, 0.5,
                        0.1
                    )
                }

                val currentLives = getGemLives(clickedItem)
                updateFireGemLore(player, currentLives)
            }
        }
    }
    private fun getPlayerLives(player: Player): Int {
        val container = player.persistentDataContainer
        return if (container.has(playerLivesKey, PersistentDataType.INTEGER)) {
            container.get(playerLivesKey, PersistentDataType.INTEGER) ?: DEFAULT_LIVES
        } else {
            setPlayerLives(player, DEFAULT_LIVES)
            DEFAULT_LIVES
        }
    }

    private fun setPlayerLives(player: Player, lives: Int) {
        val container = player.persistentDataContainer
        container.set(playerLivesKey, PersistentDataType.INTEGER, lives)

        updateFireGemLore(player)
    }

    private fun isFireballUnlocked(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        val container = meta.persistentDataContainer
        return container.get(fireballUnlockKey, PersistentDataType.INTEGER) == 1
    }

    private fun getEnergyCount(item: ItemStack): Int {
        val meta = item.itemMeta ?: return 0
        val container = meta.persistentDataContainer
        return container.get(energyCountKey, PersistentDataType.INTEGER) ?: 0
    }

    private fun setEnergyCount(item: ItemStack, count: Int) {
        val meta = item.itemMeta ?: return
        val container = meta.persistentDataContainer
        container.set(energyCountKey, PersistentDataType.INTEGER, count)
        item.itemMeta = meta
    }

    private fun unlockFireball(item: ItemStack) {
        val meta = item.itemMeta ?: return
        val container = meta.persistentDataContainer
        container.set(fireballUnlockKey, PersistentDataType.INTEGER, 1)

        val lore = meta.lore ?: mutableListOf()
        val updatedLore = mutableListOf<String>()

        for (line in lore) {
            if (line.contains("Fireball:", ignoreCase = true)) {
                updatedLore.add("${ChatColor.GOLD}Fireball: ${ChatColor.GREEN}Unlocked")
            } else {
                updatedLore.add(line)
            }
        }

        meta.lore = updatedLore
        item.itemMeta = meta
    }

    private fun updateFireGemLore(player: Player, forcedLives: Int? = null) {
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            if (isFireGem(item)) {
                val meta = item.itemMeta ?: continue
                val lore = meta.lore ?: continue
                val updatedLore = mutableListOf<String>()

                val lives = forcedLives ?: getPlayerLives(player)
                val energyCount = getEnergyCount(item)
                val isUnlocked = isFireballUnlocked(item)

                for (line in lore) {
                    when {
                        line.contains("Lives:", ignoreCase = true) -> {
                            updatedLore.add("${ChatColor.GOLD}Lives: ${ChatColor.WHITE}$lives/${MAX_LIVES}")
                        }
                        line.contains("Fireball:", ignoreCase = true) -> {
                            if (isUnlocked) {
                                updatedLore.add("${ChatColor.GOLD}Fireball: ${ChatColor.GREEN}Unlocked")
                            } else {
                                updatedLore.add("${ChatColor.GOLD}Fireball: ${ChatColor.RED}Locked ${ChatColor.WHITE}(Requires ${ENERGY_REQUIRED} Energy) ${ChatColor.YELLOW}[$energyCount/$ENERGY_REQUIRED]")
                            }
                        }
                        else -> updatedLore.add(line)
                    }
                }

                meta.lore = updatedLore
                item.itemMeta = meta
                return
            }
        }
    }

    private fun startChargingTask() {
        object : BukkitRunnable() {
            override fun run() {
                val playersToRemove = mutableListOf<UUID>()

                for ((playerUuid, charge) in chargingPlayers) {
                    val player = Bukkit.getPlayer(playerUuid)

                    if (player == null || !player.isOnline || !isPlayerHoldingFireGem(player)) {
                        playersToRemove.add(playerUuid)
                        continue
                    }

                    val heldItem = player.inventory.itemInMainHand
                    val offHandItem = player.inventory.itemInOffHand

                    val gemItem = if (isFireGem(heldItem)) heldItem else offHandItem

                    if (!isFireballUnlocked(gemItem)) {
                        player.sendMessage("${ChatColor.RED}You need to unlock Fireball by adding ${ENERGY_REQUIRED} Bottled Of Energy to your Fire Gem!")
                        playersToRemove.add(playerUuid)
                        continue
                    }

                    val chargeRate = if (isNearFire(player)) CHARGE_RATE_BOOST else CHARGE_RATE

                    val newCharge = minOf(charge + chargeRate, MAX_CHARGE)
                    chargingPlayers[playerUuid] = newCharge

                    val progressBar = createProgressBar(newCharge, MAX_CHARGE)
                    player.sendActionBar("${ChatColor.GOLD}Charging Fireball: $progressBar ${ChatColor.YELLOW}$newCharge%")

                    if (newCharge >= MAX_CHARGE) {
                        shootFireball(player, newCharge)
                        playersToRemove.add(playerUuid)
                    }
                }

                playersToRemove.forEach { chargingPlayers.remove(it) }
            }
        }.runTaskTimer(plugin, 5L, 5L)
    }

    private fun createProgressBar(current: Int, max: Int): String {
        val barLength = 10
        val progress = (current.toDouble() / max * barLength).toInt()

        return buildString {
            append(ChatColor.RED)
            repeat(progress) { append('|') }
            append(ChatColor.GRAY)
            repeat(barLength - progress) { append('|') }
        }
    }

    private fun isNearFire(player: Player): Boolean {
        val location = player.location
        val world = location.world

        for (x in -2..2) {
            for (y in -2..2) {
                for (z in -2..2) {
                    val block = world.getBlockAt(location.blockX + x, location.blockY + y, location.blockZ + z)
                    val type = block.type

                    if (type == Material.FIRE || type == Material.LAVA || type == Material.CAMPFIRE ||
                        type == Material.SOUL_CAMPFIRE || type == Material.MAGMA_BLOCK) {
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun isPlayerHoldingFireGem(player: Player): Boolean {
        return isFireGem(player.inventory.itemInMainHand) || isFireGem(player.inventory.itemInOffHand)
    }

    private fun hasFireGemInInventory(player: Player): Boolean {
        for (item in player.inventory.contents) {
            if (isFireGem(item)) {
                return true
            }
        }
        return false
    }

    @EventHandler
    fun onPlayerItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        val item = player.inventory.getItem(event.newSlot)

        if (isFireGem(item)) {
            var count = 0
            for (invItem in player.inventory.contents) {
                if (isFireGem(invItem)) {
                    count++
                }
            }

            if (count > 1) {
                if (item != null) {
                    player.inventory.remove(item)
                }
            }
        }

        autoEnchantEquippedItems(player)
    }

    private fun autoEnchantEquippedItems(player: Player) {
        if (!hasFireGemInInventory(player)) return

        val mainHandItem = player.inventory.itemInMainHand
        if (mainHandItem.type != Material.AIR) {
            when (mainHandItem.type) {
                Material.BOW -> {
                    if (!mainHandItem.enchantments.containsKey(org.bukkit.enchantments.Enchantment.FLAME)) {
                        mainHandItem.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.FLAME, 1)
                        player.world.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.0f)
                    }
                }
                Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD,
                Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD -> {
                    if (!mainHandItem.enchantments.containsKey(org.bukkit.enchantments.Enchantment.FIRE_ASPECT)) {
                        mainHandItem.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.FIRE_ASPECT, 2)
                        player.world.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.0f)
                    }
                }
                else -> { /* Do nothing */ }
            }
        }
    }
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val mainHandItem = player.inventory.itemInMainHand
        val offHandItem = player.inventory.itemInOffHand

        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            val gemItem: ItemStack?
            val energyItem: ItemStack?

            if (isFireGem(mainHandItem) && isBottledOfEnergy(offHandItem)) {
                gemItem = mainHandItem
                energyItem = offHandItem
                event.isCancelled = true
            } else if (isFireGem(offHandItem) && isBottledOfEnergy(mainHandItem)) {
                gemItem = offHandItem
                energyItem = mainHandItem
                event.isCancelled = true
            } else {
                gemItem = null
                energyItem = null
            }

            if (gemItem != null && energyItem != null) {
                if (isFireballUnlocked(gemItem)) {
                    player.sendMessage("${ChatColor.YELLOW}Fireball is already unlocked!")
                } else {
                    val currentEnergy = getEnergyCount(gemItem)
                    if (currentEnergy < ENERGY_REQUIRED) {
                        setEnergyCount(gemItem, currentEnergy + 1)

                        energyItem.amount -= 1

                        player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
                        player.world.spawnParticle(
                            org.bukkit.Particle.FLAME,
                            player.location.add(0.0, 1.0, 0.0),
                            10,
                            0.5, 0.5, 0.5,
                            0.05
                        )

                        player.sendMessage("${ChatColor.GOLD}Added energy to Fire Gem! (${currentEnergy + 1}/$ENERGY_REQUIRED)")

                        if (currentEnergy + 1 >= ENERGY_REQUIRED) {
                            unlockFireball(gemItem)
                            player.sendMessage("${ChatColor.GREEN}${ChatColor.BOLD}FIREBALL UNLOCKED!")
                            player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.5f)

                            player.world.spawnParticle(
                                org.bukkit.Particle.LAVA,
                                player.location.add(0.0, 1.0, 0.0),
                                50,
                                1.0, 1.0, 1.0,
                                0.1
                            )
                        }

                        updateFireGemLore(player)
                    }
                }
                return
            }
        }

        val isInMainHand = isFireGem(mainHandItem)
        val isInOffHand = isFireGem(offHandItem)

        if (!isInMainHand && !isInOffHand) return

        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val usedHand = if (event.hand == EquipmentSlot.OFF_HAND) EquipmentSlot.OFF_HAND else EquipmentSlot.HAND

        if ((usedHand == EquipmentSlot.HAND && !isInMainHand) ||
            (usedHand == EquipmentSlot.OFF_HAND && !isInOffHand)) return

        event.isCancelled = true

        val gemItem = if (usedHand == EquipmentSlot.HAND) mainHandItem else offHandItem

        if (player.isSneaking) {
            spawnCampfire(player)
        } else {
            if (isFireballUnlocked(gemItem)) {
                startChargingFireball(player)
            } else {
                val energyCount = getEnergyCount(gemItem)
                player.sendMessage("${ChatColor.RED}Fireball is locked! Add ${ENERGY_REQUIRED - energyCount} more Bottled Of Energy to unlock.")
            }
        }
    }

    @EventHandler
    fun onPlayerToggleSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) {
            val player = event.player
            if (chargingPlayers.containsKey(player.uniqueId)) {
                val charge = chargingPlayers[player.uniqueId] ?: 0
                shootFireball(player, charge)
                chargingPlayers.remove(player.uniqueId)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onEntityDamageByEntityEvent(event: EntityDamageByEntityEvent) {
        if (event.entity is Player) {
            val player = event.entity as Player

            if (hasFireGemInInventory(player)) {
                if (Math.random() < FIRE_THORNS_CHANCE && event.damager is Entity) {
                    val attacker = event.damager as Entity
                    attacker.fireTicks = 100
                    player.world.playSound(player.location, Sound.ENTITY_BLAZE_BURN, 1.0f, 1.0f)

                    player.world.spawnParticle(
                        org.bukkit.Particle.FLAME,
                        player.location.add(0.0, 1.0, 0.0),
                        20,
                        0.5, 0.5, 0.5,
                        0.05
                    )
                }
            }
        }
    }

    @EventHandler
    fun onEntityDamage(event: org.bukkit.event.entity.EntityDamageEvent) {
        if (event.entity is Player &&
            (event.cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE ||
                    event.cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE_TICK)) {

            val player = event.entity as Player

            val playerLoc = player.location
            for (x in -1..1) {
                for (y in -1..1) {
                    for (z in -1..1) {
                        val block = playerLoc.world.getBlockAt(
                            playerLoc.blockX + x,
                            playerLoc.blockY + y,
                            playerLoc.blockZ + z
                        )

                        if (block.type == Material.CAMPFIRE &&
                            block.hasMetadata(FIRE_GEM_CAMPFIRE_METADATA)) {
                            event.isCancelled = true

                            player.fireTicks = 0
                            return
                        }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block

        if (block.type == Material.CAMPFIRE && block.hasMetadata(FIRE_GEM_CAMPFIRE_METADATA)) {
            event.isCancelled = true
            return
        }

        val player = event.player

        if (!hasFireGemInInventory(player)) return

        val drops = block.getDrops(player.inventory.itemInMainHand)
        if (drops.isEmpty()) return

        val blockLocation = block.location

        when (block.type) {
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.RAW_IRON_BLOCK -> {
                event.isDropItems = false

                val dropAmount = if (block.type == Material.RAW_IRON_BLOCK) 9 else 1

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    blockLocation.world.dropItemNaturally(blockLocation, ItemStack(Material.IRON_INGOT, dropAmount))
                    player.sendMessage("${ChatColor.GOLD}Your Fire Gem smelted ${
                        block.type.name.lowercase(Locale.getDefault()).replace('_', ' ')}!")
                })
            }

            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.RAW_GOLD_BLOCK -> {
                event.isDropItems = false

                val dropAmount = if (block.type == Material.RAW_GOLD_BLOCK) 9 else 1

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    blockLocation.world.dropItemNaturally(blockLocation, ItemStack(Material.GOLD_INGOT, dropAmount))
                    player.sendMessage("${ChatColor.GOLD}Your Fire Gem smelted ${
                        block.type.name.lowercase(Locale.getDefault()).replace('_', ' ')}!")
                })
            }

            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, Material.RAW_COPPER_BLOCK -> {
                event.isDropItems = false

                val dropAmount = when (block.type) {
                    Material.RAW_COPPER_BLOCK -> 9
                    else -> 2 + (Math.random() * 2).toInt()
                }

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    blockLocation.world.dropItemNaturally(blockLocation, ItemStack(Material.COPPER_INGOT, dropAmount))
                    player.sendMessage("${ChatColor.GOLD}Your Fire Gem smelted ${
                        block.type.name.lowercase(Locale.getDefault()).replace('_', ' ')}!")
                })
            }

            Material.ANCIENT_DEBRIS -> {
                event.isDropItems = false

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    blockLocation.world.dropItemNaturally(blockLocation, ItemStack(Material.NETHERITE_SCRAP, 1))
                    player.sendMessage("${ChatColor.GOLD}Your Fire Gem smelted ancient debris!")
                })
            }
            else -> { /* Do nothing for other blocks */ }
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity

        event.drops.removeIf { isFireGem(it) }

        for (item in player.inventory.contents) {
            if (item != null && isFireGem(item)) {
                val lives = getGemLives(item)

                if (lives <= 1) {
                    setGemLives(item, 0)
                    player.sendMessage("${ChatColor.RED}Your Fire Gem has shattered! (0 lives remaining)")
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.banPlayer("${ChatColor.RED}You have lost all your lives! Your gem has been destroyed!")
                    })
                } else {
                    val updatedGem = item.clone()
                    setGemLives(updatedGem, lives - 1)
                    plugin.gemsToBeSaved[player.uniqueId] = updatedGem
                    player.sendMessage("${ChatColor.GOLD}Your Fire Gem lost a life! (${lives - 1}/$MAX_LIVES lives)")
                }
                break
            }
        }
    }



    @EventHandler
    fun onPlayerKill(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity !is Player) return

        val killer = entity.killer
        if (killer is Player) {
            val fireGem = findFireGemInHands(killer)
            if (fireGem != null) {
                var lives = getGemLives(fireGem)
                if (lives < MAX_LIVES) {
                    lives++
                    setGemLives(fireGem, lives)
                    killer.sendMessage("${ChatColor.GREEN}You gained a life for killing ${entity.name}! Current lives: ${ChatColor.WHITE}$lives/$MAX_LIVES")
                } else {
                    killer.sendMessage("${ChatColor.YELLOW}You already have the maximum number of lives ($MAX_LIVES)!")
                }
            }
        }
    }


    private fun startChargingFireball(player: Player) {
        val currentTime = System.currentTimeMillis()
        val lastUse = fireballCooldowns[player.uniqueId] ?: 0L

        if (currentTime - lastUse < FIREBALL_COOLDOWN) {
            val remainingCooldown = ((FIREBALL_COOLDOWN - (currentTime - lastUse)) / 1000) + 1
            player.sendMessage("${ChatColor.RED}Fireball is on cooldown for $remainingCooldown seconds!")
            return
        }

        if (!chargingPlayers.containsKey(player.uniqueId)) {
            chargingPlayers[player.uniqueId] = 0
            player.sendMessage("${ChatColor.GOLD}Charging fireball... Release shift to fire!")
            player.world.playSound(player.location, Sound.BLOCK_FIRE_AMBIENT, 1.0f, 1.0f)
        }
    }

    private fun shootFireball(player: Player, charge: Int) {
        if (charge < 10) {
            player.sendMessage("${ChatColor.RED}Fireball fizzled out. Charge it longer!")
            return
        }

        val chargePercentage = (charge.toFloat() / MAX_CHARGE * 100).coerceIn(5f, 100f)
        val yield = (charge / 20.0).coerceAtMost(5.0).toFloat()

        val fireball = player.world.spawn(player.eyeLocation, Fireball::class.java)
        fireball.shooter = player
        fireball.direction = player.location.direction
        fireball.yield = yield
        fireball.velocity = player.location.direction.multiply(0.5)

        fireball.setMetadata("charge_percentage", FixedMetadataValue(plugin, chargePercentage))

        player.world.playSound(player.location, Sound.ENTITY_GHAST_SHOOT, 1.0f, 0.5f)

        fireballCooldowns[player.uniqueId] = System.currentTimeMillis()
    }

    private fun spawnCampfire(player: Player) {
        val currentTime = System.currentTimeMillis()
        val lastUse = campfireCooldowns[player.uniqueId] ?: 0L

        if (currentTime - lastUse < CAMPFIRE_COOLDOWN) {
            val remainingCooldown = ((CAMPFIRE_COOLDOWN - (currentTime - lastUse)) / 1000) + 1
            player.sendMessage("${ChatColor.RED}Cozy Campfire is on cooldown for $remainingCooldown seconds!")
            return
        }

        val targetBlock = player.getTargetBlock(null, 5)
        if (targetBlock.type == Material.AIR || !targetBlock.type.isSolid) {
            player.sendMessage("${ChatColor.RED}You must target a solid block!")
            return
        }

        val campfireLocation = targetBlock.location.add(0.0, 1.0, 0.0)
        if (campfireLocation.block.type != Material.AIR) {
            player.sendMessage("${ChatColor.RED}There's no space for a campfire here!")
            return
        }

        campfireLocation.block.type = Material.CAMPFIRE

        campfireLocation.block.setMetadata(FIRE_GEM_CAMPFIRE_METADATA,
            FixedMetadataValue(plugin, true))

        campfireCooldowns[player.uniqueId] = System.currentTimeMillis()

        object : BukkitRunnable() {
            private var timer = 0
            private val maxTime = 20 * 10

            override fun run() {
                if (timer >= maxTime || campfireLocation.block.type != Material.CAMPFIRE) {
                    if (campfireLocation.block.type == Material.CAMPFIRE) {
                        campfireLocation.block.type = Material.AIR
                    }
                    cancel()
                    return
                }

                for (entity in campfireLocation.world.getNearbyEntities(campfireLocation, 4.0, 4.0, 4.0)) {
                    if (entity is Player) {
                        val playerHealth = entity.health
                        val maxHealth = entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0

                        if (playerHealth < maxHealth) {
                            entity.health = Math.min(playerHealth + 2, maxHealth)
                        }

                        val foodLevel = entity.foodLevel
                        if (foodLevel < 20) {
                            entity.foodLevel = Math.min(foodLevel + 2, 20)
                        }

                        entity.world.spawnParticle(
                            org.bukkit.Particle.HEART,
                            entity.location.add(0.0, 1.0, 0.0),
                            1,
                            0.5, 0.5, 0.5
                        )
                    }
                }

                timer += 20
            }
        }.runTaskTimer(plugin, 0L, 20L)

        player.sendMessage("${ChatColor.GOLD}You created a Cozy Campfire that will heal nearby players!")
        player.world.playSound(campfireLocation, Sound.BLOCK_CAMPFIRE_CRACKLE, 1.0f, 1.0f)
    }
}