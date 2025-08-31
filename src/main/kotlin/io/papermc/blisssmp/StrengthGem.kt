package io.papermc.blisssmp


import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerPickupItemEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.HashMap
import java.util.UUID
import kotlin.compareTo

class StrengthGem(private val plugin: BlissSMPPlugin) : Listener {

    private val strengthGemKey: NamespacedKey = NamespacedKey(plugin, "strength_gem")
    private val gemLivesKey: NamespacedKey = NamespacedKey(plugin, "gem_lives")
    private val anyGemKey: NamespacedKey = NamespacedKey(plugin, "any_gem")
    private val frailerEnergyKey: NamespacedKey = NamespacedKey(plugin, "frailer_energy")
    private val frailerUnlockedKey: NamespacedKey = NamespacedKey(plugin, "frailer_unlocked")
    private val ENERGY_REQUIRED = 10
    private val critCounter = HashMap<UUID, Int>()
    private val superCritCounter = HashMap<UUID, Int>()
    private val gemCooldowns = HashMap<UUID, Long>()
    private val gemsToBeSaved = HashMap<UUID, ItemStack>()
    private val DEFAULT_LIVES = 5
    private val MAX_LIVES = 10

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        startStrengthEffectScheduler()
    }

    private fun startStrengthEffectScheduler() {
        object : BukkitRunnable() {
            override fun run() {
                for (player in plugin.server.onlinePlayers) {
                    if (hasStrengthGemInInventory(player)) {
                        player.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, 300, 1, false, false))
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 200L)
    }

    fun createStrengthGem(): ItemStack {
        val strengthGem = ItemStack(Material.REDSTONE)
        val meta = strengthGem.itemMeta

        if (meta != null) {
            meta.setDisplayName("${ChatColor.RED}${ChatColor.BOLD}Strength Gem")
            meta.lore = listOf(
                "${ChatColor.GRAY}A powerful gem that enhances your physical strength.",
                "",
                "${ChatColor.RED}Lives: ${ChatColor.WHITE}$DEFAULT_LIVES/${MAX_LIVES}",
                "${ChatColor.RED}FRAILER: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ENERGY_REQUIRED Energy)",
                "",
                "${ChatColor.RED}Passives:",
                "${ChatColor.WHITE}• PRISTINE: Permanent Strength II effect",
                "${ChatColor.WHITE}• Auto-enchants all melee weapons (Swords & Axes) to Sharpness V",
                "",
                "${ChatColor.RED}Powers:",
                "${ChatColor.WHITE}• FRAILER: Clear enemy potion effects, apply Weakness I (20s) and Wither (40s) (Right-Click)",
                "${ChatColor.WHITE}• CHAD STRENGTH: Every 3 crits charges 2x damage hit, every 8 crits charges 3x damage hit",
                "",
                "${ChatColor.YELLOW}• Kill players to gain lives (Max: ${MAX_LIVES})",
                "${ChatColor.YELLOW}• Lose a life when you die",
            )
            meta.isUnbreakable = true
            meta.addEnchant(Enchantment.UNBREAKING, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE)

            meta.persistentDataContainer.set(strengthGemKey, PersistentDataType.BYTE, 1)
            meta.persistentDataContainer.set(anyGemKey, PersistentDataType.BYTE, 1)
            meta.persistentDataContainer.set(gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
            meta.persistentDataContainer.set(frailerEnergyKey, PersistentDataType.INTEGER, 0)
            meta.persistentDataContainer.set(frailerUnlockedKey, PersistentDataType.BYTE, 0)

            strengthGem.itemMeta = meta
        }

        return strengthGem
    }

    private fun isFrailerUnlocked(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.getOrDefault(frailerUnlockedKey, PersistentDataType.BYTE, 0) == 1.toByte()
    }

    private fun getFrailerEnergy(item: ItemStack): Int {
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(frailerEnergyKey, PersistentDataType.INTEGER, 0)
    }

    private fun setFrailerEnergy(item: ItemStack, count: Int) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(frailerEnergyKey, PersistentDataType.INTEGER, count)
        item.itemMeta = meta
    }

    private fun unlockFrailer(item: ItemStack) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(frailerUnlockedKey, PersistentDataType.BYTE, 1)
        val lore = meta.lore?.toMutableList() ?: return
        for (i in lore.indices) {
            if (lore[i].contains("FRAILER:")) {
                lore[i] = "${ChatColor.RED}FRAILER: ${ChatColor.GREEN}Unlocked"
            }
        }
        meta.lore = lore
        item.itemMeta = meta
    }

    private fun updateStrengthGemLore(item: ItemStack) {
        val meta = item.itemMeta ?: return
        val lore = meta.lore?.toMutableList() ?: return
        val energy = getFrailerEnergy(item)
        val unlocked = isFrailerUnlocked(item)
        for (i in lore.indices) {
            if (lore[i].contains("FRAILER:")) {
                lore[i] = if (unlocked) {
                    "${ChatColor.RED}FRAILER: ${ChatColor.GREEN}Unlocked"
                } else {
                    "${ChatColor.RED}FRAILER: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ENERGY_REQUIRED Energy) ${ChatColor.YELLOW}[$energy/$ENERGY_REQUIRED]"
                }
            }
        }
        meta.lore = lore
        item.itemMeta = meta
    }

    fun isStrengthGem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.REDSTONE) return false

        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(strengthGemKey, PersistentDataType.BYTE)
    }

    fun isAnyGem(item: ItemStack?): Boolean {
        if (item == null) return false

        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(anyGemKey, PersistentDataType.BYTE) ||
                meta.persistentDataContainer.has(strengthGemKey, PersistentDataType.BYTE) ||
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
        if (!isStrengthGem(item)) return 0
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
    }

    private fun setGemLives(item: ItemStack, lives: Int): ItemStack {
        if (!isStrengthGem(item)) return item

        val meta = item.itemMeta ?: return item
        val adjustedLives = lives.coerceIn(0, MAX_LIVES)
        meta.persistentDataContainer.set(gemLivesKey, PersistentDataType.INTEGER, adjustedLives)

        val lore = meta.lore ?: mutableListOf()
        if (lore.isNotEmpty() && lore.size > 2) {
            lore[2] = "${ChatColor.RED}Lives: ${ChatColor.WHITE}$adjustedLives/$MAX_LIVES"
        }
        meta.lore = lore

        item.itemMeta = meta
        return item
    }

    private fun hasStrengthGemInInventory(player: Player): Boolean {
        for (item in player.inventory.contents) {
            if (item != null && isStrengthGem(item)) {
                return true
            }
        }
        return false
    }

    private fun isGemOnCooldown(player: Player): Boolean {
        val cooldownEnd = gemCooldowns[player.uniqueId] ?: return false
        return System.currentTimeMillis() < cooldownEnd
    }

    private fun getRemainingCooldown(player: Player): Long {
        val cooldownEnd = gemCooldowns[player.uniqueId] ?: return 0
        return (cooldownEnd - System.currentTimeMillis()) / 1000
    }

    private fun enchantItem(item: ItemStack) {
        if (!isWeapon(item)) return

        val meta = item.itemMeta ?: return

        if (meta.getEnchantLevel(Enchantment.SHARPNESS) < 5) {
            meta.addEnchant(Enchantment.SHARPNESS, 5, true)
        }

        if (!meta.hasEnchant(Enchantment.MENDING)) {
            meta.addEnchant(Enchantment.MENDING, 1, true)
        }

        item.itemMeta = meta
    }


    private fun isWeapon(item: ItemStack): Boolean {
        val type = item.type
        return type.name.contains("SWORD")
    }

    @EventHandler
    fun onItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        val newItem = player.inventory.getItem(event.newSlot) ?: return

        if (hasStrengthGemInInventory(player)) {
            if (isWeapon(newItem)) {
                enchantItem(newItem)
            }
            scanAndEnchantInventory(player)
        }
    }
    private fun scanAndEnchantInventory(player: Player) {
        for (item in player.inventory.contents) {
            if (item != null && isWeapon(item)) {
                enchantItem(item)
            }
        }
    }
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return

        if (isStrengthGem(item) && event.action.name.contains("RIGHT_CLICK")) {
            event.isCancelled = true
            if (!isFrailerUnlocked(item)) {
                player.sendMessage("${ChatColor.RED}FRAILER is locked! Add $ENERGY_REQUIRED Bottled Of Energy to unlock.")
                return
            }
            activateFrailerAbility(player)
        }
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

        if (isStrengthGem(clickedItem) && isBottledOfEnergy(cursorItem)) {
            event.isCancelled = true
            if (isFrailerUnlocked(clickedItem!!)) {
                player.sendMessage("${ChatColor.YELLOW}FRAILER is already unlocked!")
                return
            }

            val currentEnergy = getFrailerEnergy(clickedItem)
            if (currentEnergy < ENERGY_REQUIRED) {
                setFrailerEnergy(clickedItem, currentEnergy + 1)
                cursorItem!!.amount -= 1
                val newEnergy = currentEnergy + 1

                player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
                player.sendMessage("${ChatColor.AQUA}Added energy to Strength Gem! (${newEnergy}/$ENERGY_REQUIRED)")

                if (newEnergy >= ENERGY_REQUIRED) {
                    unlockFrailer(clickedItem)
                    player.sendMessage("${ChatColor.GREEN}${ChatColor.BOLD}FRAILER UNLOCKED!")
                    player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.5f)
                }

                updateStrengthGemLore(clickedItem)
            }
            return
        }

        if (isAnyGem(clickedItem) || isAnyGem(cursorItem)) {
            var gemCount = 0
            for (item in player.inventory.contents) {
                if (item != null && isAnyGem(item) && item != clickedItem) {
                    gemCount++
                }
            }

            val wouldHaveMultipleGems =
                (isAnyGem(clickedItem) && gemCount > 0) ||
                        (isAnyGem(cursorItem) && gemCount > 0) ||
                        (isAnyGem(clickedItem) && isAnyGem(cursorItem))

            if (wouldHaveMultipleGems) {
                event.isCancelled = true
                player.sendMessage("${ChatColor.RED}You can only carry one gem at a time!")
                return
            }
        }

        if (hasStrengthGemInInventory(player)) {
            scanAndEnchantInventory(player)
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
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (event.damager !is Player) return
        val player = event.damager as Player
        val victim = event.entity

        if (!hasStrengthGemInInventory(player)) return

        if (player.fallDistance > 0.0f && !player.isOnGround && !player.isInWater && !player.location.block.type.name.contains("LADDER")) {
            critCounter[player.uniqueId] = (critCounter[player.uniqueId] ?: 0) + 1
            superCritCounter[player.uniqueId] = (superCritCounter[player.uniqueId] ?: 0) + 1

            if ((critCounter[player.uniqueId] ?: 0) >= 3) {
                event.damage *= 2.0
                player.sendMessage("${ChatColor.RED}CHAD STRENGTH: 2x damage dealt! (${event.finalDamage} damage)")
                player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f)
                critCounter[player.uniqueId] = 0
            }

            if ((superCritCounter[player.uniqueId] ?: 0) >= 8) {
                event.damage *= 3.0
                player.sendMessage("${ChatColor.DARK_RED}CHAD STRENGTH: 3x damage dealt! (${event.finalDamage} damage)")
                player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.5f)
                player.world.playSound(player.location, Sound.ENTITY_WITHER_BREAK_BLOCK, 0.5f, 1.5f)
                superCritCounter[player.uniqueId] = 0
            }
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity

        for (item in player.inventory.contents) {
            if (item != null && isStrengthGem(item)) {
                val gem = item.clone()
                val lives = getGemLives(gem)

                if (lives > 0) {
                    val updatedGem = setGemLives(gem, lives - 1)
                    gemsToBeSaved[player.uniqueId] = updatedGem
                    player.sendMessage("${ChatColor.RED}Your Strength Gem lost a life! (${lives - 1}/$MAX_LIVES lives remaining)")
                    event.drops.removeIf { isStrengthGem(it) }
                } else {
                    player.sendMessage("${ChatColor.RED}Your Strength Gem has been destroyed! (0 lives remaining)")
                    player.banPlayer("${ChatColor.RED}You have lost all your lives! Your gem has been destroyed!")
                }
                break
            }
        }

        val killer = player.killer
        if (killer != null) {
            for (item in killer.inventory.contents) {
                if (item != null && isStrengthGem(item)) {
                    val lives = getGemLives(item)
                    if (lives < MAX_LIVES) {
                        setGemLives(item, lives + 1)
                        killer.sendMessage("${ChatColor.RED}Your Strength Gem gained a life! (${lives + 1}/$MAX_LIVES lives)")
                    } else {
                        killer.sendMessage("${ChatColor.YELLOW}Your Strength Gem is already at maximum lives! ($MAX_LIVES/$MAX_LIVES lives)")
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
                        player.sendMessage("${ChatColor.RED}Your Strength Gem has returned to you!")
                        gemsToBeSaved.remove(player.uniqueId)
                    }
                }
            }.runTaskLater(plugin, 5L)
        }
    }

    private fun activateFrailerAbility(player: Player) {
        if (isGemOnCooldown(player)) {
            val timeLeft = getRemainingCooldown(player)
            player.sendMessage("${ChatColor.RED}Your Strength Gem is on cooldown for ${timeLeft} more seconds!")
            return
        }

        gemCooldowns[player.uniqueId] = System.currentTimeMillis() + 30000

        val nearbyEntities = player.getNearbyEntities(5.0, 5.0, 5.0)
            .filterIsInstance<LivingEntity>()
            .filter { it !is Player || (it as Player).uniqueId != player.uniqueId }

        if (nearbyEntities.isEmpty()) {
            player.sendMessage("${ChatColor.RED}No targets found nearby!")
            return
        }

        player.world.playSound(player.location, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.8f)
        player.sendMessage("${ChatColor.RED}You activate FRAILER ability!")

        for (entity in nearbyEntities) {
            for (effect in entity.activePotionEffects) {
                entity.removePotionEffect(effect.type)
            }

            entity.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, 20 * 20, 0, false, true))
            entity.addPotionEffect(PotionEffect(PotionEffectType.WITHER, 40 * 20, 0, false, true))

            entity.world.spawnParticle(
                org.bukkit.Particle.DUST,
                entity.location.add(0.0, 1.0, 0.0),
                30,
                0.5,
                1.0,
                0.5,
                0.05,
                org.bukkit.Particle.DustOptions(org.bukkit.Color.RED, 1.0f)
            )
            entity.world.playSound(entity.location, Sound.ENTITY_WITHER_HURT, 0.5f, 1.5f)

            if (entity is Player) {
                entity.sendMessage("${ChatColor.RED}A Strength Gem's FRAILER ability has weakened you!")
            }
        }
    }

    private fun isMeleeWeapon(item: ItemStack): Boolean {
        val type = item.type
        return type.name.contains("SWORD") || type.name.contains("AXE")
    }


}