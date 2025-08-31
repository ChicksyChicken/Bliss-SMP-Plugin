package io.papermc.blisssmp

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.FurnaceExtractEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerPickupItemEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantInventory
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.collections.remove
import kotlin.compareTo
import kotlin.text.compareTo
import kotlin.text.set

class WealthGem(private val plugin: BlissSMPPlugin) : Listener {

    private val wealthGemKey: NamespacedKey = NamespacedKey(plugin, "rich_gem")
    private val gemLivesKey: NamespacedKey = NamespacedKey(plugin, "gem_lives")
    private val anyGemKey: NamespacedKey = NamespacedKey(plugin, "any_gem")
    private val discountedTraderKey: NamespacedKey = NamespacedKey(plugin, "discounted_trader")
    private val gemCooldowns = mutableMapOf<UUID, MutableMap<String, Long>>()
    private val UNFORTUNATE_COOLDOWN = 30000L
    private val RICH_RUSH_COOLDOWN = 60000L
    private val richRushActive = HashSet<UUID>()
    private val gemsToBeSaved = HashMap<UUID, ItemStack>()
    private val DEFAULT_LIVES = 5
    private val MAX_LIVES = 10
    private val VILLAGER_DISCOUNT_PERCENT = 30
    private val wealthGemEnergyKey: NamespacedKey = NamespacedKey(plugin, "rich_gem_energy")
    private val wealthGemUnlockedKey: NamespacedKey = NamespacedKey(plugin, "rich_gem_unlocked")
    private val ENERGY_REQUIRED = 10

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        startLuckEffectScheduler()
    }

    private fun startLuckEffectScheduler() {
        object : BukkitRunnable() {
            override fun run() {
                for (player in plugin.server.onlinePlayers) {
                    if (hasWealthGemInInventory(player)) {
                        player.addPotionEffect(PotionEffect(PotionEffectType.LUCK, 300, 1, false, false))
                        player.addPotionEffect(PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 300, 1, false, false))
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 200L)
    }

    fun createWealthGem(): ItemStack {
        val wealthGem = ItemStack(Material.EMERALD)
        val meta = wealthGem.itemMeta

        if (meta != null) {
            meta.setDisplayName("${ChatColor.GREEN}${ChatColor.BOLD}Wealth Gem")
            meta.lore = listOf(
                "${ChatColor.GRAY}A valuable gem that increases your fortune.",
                "",
                "${ChatColor.GREEN}Lives: ${ChatColor.WHITE}$DEFAULT_LIVES/${MAX_LIVES}",
                "",
                "${ChatColor.GREEN}Passives:",
                "${ChatColor.WHITE}• PRISTINE: Permanent Luck effect",
                "${ChatColor.WHITE}• ${VILLAGER_DISCOUNT_PERCENT}% cheaper villager trades",
                "${ChatColor.WHITE}• Auto-enchants tools with Fortune III, and Mending",
                "${ChatColor.WHITE}• Auto-enchants weapons with Looting III, and Mending",
                "${ChatColor.WHITE}• Get two extra ore for every 3 ores mined",
                "${ChatColor.WHITE}• 2x Netherite scrap from furnaces",
                "${ChatColor.WHITE}• Chip away enemies' equipment durability 2x faster",
                "",
                "${ChatColor.GREEN}Powers:",
                "${ChatColor.WHITE}• UNFORTUNATE: Make enemies cancel 1/3 of actions for 40s (Right-Click)",
                "${ChatColor.WHITE}• RICH RUSH: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ENERGY_REQUIRED Energy)",
                "",
                "${ChatColor.YELLOW}• Kill players to gain lives (Max: ${MAX_LIVES})",
                "${ChatColor.YELLOW}• Lose a life when you die",
            )
            meta.isUnbreakable = true
            meta.addEnchant(Enchantment.UNBREAKING, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE)

            meta.persistentDataContainer.set(wealthGemKey, PersistentDataType.BYTE, 1)
            meta.persistentDataContainer.set(wealthGemEnergyKey, PersistentDataType.INTEGER, 0)
            meta.persistentDataContainer.set(wealthGemUnlockedKey, PersistentDataType.BYTE, 0)
    
            wealthGem.itemMeta = meta
            return wealthGem
        }

        return wealthGem
    }

    fun getGem(): ItemStack {
        return createWealthGem()
    }

    fun isWealthGem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.EMERALD) return false

        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(wealthGemKey, PersistentDataType.BYTE)
    }
    private fun isWealthGemUnlocked(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.getOrDefault(wealthGemUnlockedKey, PersistentDataType.BYTE, 0) == 1.toByte()
    }

    private fun getWealthGemEnergy(item: ItemStack): Int {
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(wealthGemEnergyKey, PersistentDataType.INTEGER, 0)
    }

    private fun setWealthGemEnergy(item: ItemStack, count: Int) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(wealthGemEnergyKey, PersistentDataType.INTEGER, count)
        item.itemMeta = meta
    }
    private fun updateWealthGemLore(item: ItemStack) {
        val meta = item.itemMeta ?: return
        val lore = meta.lore?.toMutableList() ?: return
        val unlocked = isWealthGemUnlocked(item)
        val energy = getWealthGemEnergy(item)
        for (i in lore.indices) {
            if (lore[i].contains("RICH RUSH:")) {
                if (unlocked) {
                    lore[i] = "${ChatColor.WHITE}• RICH RUSH: ${ChatColor.GREEN}Unlocked"
                } else {
                    lore[i] = "${ChatColor.WHITE}• RICH RUSH: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ENERGY_REQUIRED Energy) ${ChatColor.YELLOW}[$energy/$ENERGY_REQUIRED]"
                }
            }
        }
        meta.lore = lore
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


    private fun unlockWealthGem(item: ItemStack) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(wealthGemUnlockedKey, PersistentDataType.BYTE, 1)
        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        for (i in lore.indices) {
            if (lore[i].contains("RICH RUSH:")) {
                lore[i] = "${ChatColor.WHITE}• RICH RUSH: ${ChatColor.GREEN}Unlocked"
            }
        }
        meta.lore = lore
        item.itemMeta = meta
    }


    fun isAnyGem(item: ItemStack?): Boolean {
        if (item == null) return false

        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(anyGemKey, PersistentDataType.BYTE) ||
                meta.persistentDataContainer.has(wealthGemKey, PersistentDataType.BYTE) ||
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

    private fun hasWealthGemInInventory(player: Player): Boolean {
        for (item in player.inventory.contents) {
            if (item != null && isWealthGem(item)) {
                return true
            }
        }
        return false
    }
    
    private fun getGemLives(item: ItemStack): Int {
        if (!isWealthGem(item)) return 0
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
    }

    private fun setGemLives(item: ItemStack, lives: Int): ItemStack {
        if (!isWealthGem(item)) return item

        val meta = item.itemMeta ?: return item
        val adjustedLives = lives.coerceIn(0, MAX_LIVES)
        meta.persistentDataContainer.set(gemLivesKey, PersistentDataType.INTEGER, adjustedLives)

        val lore = meta.lore ?: mutableListOf()
        if (lore.isNotEmpty() && lore.size > 2) {
            lore[2] = "${ChatColor.GREEN}Lives: ${ChatColor.WHITE}$adjustedLives/$MAX_LIVES"
        }
        meta.lore = lore

        item.itemMeta = meta
        return item
    }

    private fun isGemOnCooldown(player: Player, ability: String): Boolean {
        val playerCooldowns = gemCooldowns.getOrPut(player.uniqueId) { mutableMapOf() }
        val lastUsage = playerCooldowns[ability] ?: 0L
        val cooldownDuration = when (ability) {
            "unfortunate" -> UNFORTUNATE_COOLDOWN
            "richRush" -> RICH_RUSH_COOLDOWN
            else -> 0L
        }
        return System.currentTimeMillis() < lastUsage + cooldownDuration
    }
    private fun setGemCooldown(player: Player, ability: String) {
        val playerCooldowns = gemCooldowns.getOrPut(player.uniqueId) { mutableMapOf() }
        playerCooldowns[ability] = System.currentTimeMillis()
    }
    private fun getRemainingCooldown(player: Player, ability: String): Long {
        val playerCooldowns = gemCooldowns.getOrPut(player.uniqueId) { mutableMapOf() }
        val lastUsage = playerCooldowns[ability] ?: return 0L
        val cooldownDuration = when (ability) {
            "unfortunate" -> UNFORTUNATE_COOLDOWN
            "richRush" -> RICH_RUSH_COOLDOWN
            else -> 0L
        }
        return (lastUsage + cooldownDuration - System.currentTimeMillis()) / 1000
    }

    @EventHandler
    fun onItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        val newItem = player.inventory.getItem(event.newSlot) ?: return

        if (isToolOrWeapon(newItem) && hasWealthGemInInventory(player)) {
            enchantItem(newItem)
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return

        if (isWealthGem(item) && event.action.name.contains("RIGHT_CLICK")) {
            event.isCancelled = true

            if (player.isSneaking) {
                activateRichRush(player)
            } else {
                activateUnfortunateAbility(player)
            }
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
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        val entity = event.rightClicked

        if (entity is Villager && hasWealthGemInInventory(player)) {
            val discountedForPlayer = if (entity.persistentDataContainer.has(discountedTraderKey, PersistentDataType.STRING)) {
                entity.persistentDataContainer.get(discountedTraderKey, PersistentDataType.STRING) == player.uniqueId.toString()
            } else {
                false
            }

            if (!discountedForPlayer) {
                val discountedRecipes = mutableListOf<MerchantRecipe>()
                for (recipe in entity.recipes) {
                    val discountedRecipe = MerchantRecipe(
                        recipe.result,
                        recipe.uses,
                        recipe.maxUses,
                        recipe.hasExperienceReward(),
                        recipe.villagerExperience,
                        recipe.priceMultiplier * (1.0f - VILLAGER_DISCOUNT_PERCENT / 100.0f),
                    )

                    for (ingredient in recipe.ingredients) {
                        val discountedIngredient = ingredient.clone()
                        discountedIngredient.amount = Math.max(1, (ingredient.amount * (1.0 - VILLAGER_DISCOUNT_PERCENT / 100.0)).toInt())
                        discountedRecipe.addIngredient(discountedIngredient)
                    }

                    discountedRecipes.add(discountedRecipe)
                }

                entity.recipes = discountedRecipes

                entity.persistentDataContainer.set(
                    discountedTraderKey,
                    PersistentDataType.STRING,
                    player.uniqueId.toString()
                )

                player.sendMessage("${ChatColor.GREEN}Your Wealth Gem has lowered the trader's prices by ${VILLAGER_DISCOUNT_PERCENT}%!")
            }
        }
    }

    @EventHandler
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return

        if (event.inventory is MerchantInventory && hasWealthGemInInventory(player)) {
            val merchant = (event.inventory as MerchantInventory).merchant ?: return

            if (merchant is Villager) {
                val discountedForPlayer = if (merchant.persistentDataContainer.has(discountedTraderKey, PersistentDataType.STRING)) {
                    merchant.persistentDataContainer.get(discountedTraderKey, PersistentDataType.STRING) == player.uniqueId.toString()
                } else {
                    false
                }

                if (!discountedForPlayer) {
                    val discountedRecipes = mutableListOf<MerchantRecipe>()
                    for (recipe in merchant.recipes) {
                        val discountedRecipe = MerchantRecipe(
                            recipe.result,
                            recipe.uses,
                            recipe.maxUses,
                            recipe.hasExperienceReward(),
                            recipe.villagerExperience,
                            recipe.priceMultiplier * (1.0f - VILLAGER_DISCOUNT_PERCENT / 100.0f),
                        )

                        for (ingredient in recipe.ingredients) {
                            val discountedIngredient = ingredient.clone()
                            discountedIngredient.amount = Math.max(1, (ingredient.amount * (1.0 - VILLAGER_DISCOUNT_PERCENT / 100.0)).toInt())
                            discountedRecipe.addIngredient(discountedIngredient)
                        }

                        discountedRecipes.add(discountedRecipe)
                    }

                    merchant.recipes = discountedRecipes

                    merchant.persistentDataContainer.set(
                        discountedTraderKey,
                        PersistentDataType.STRING,
                        player.uniqueId.toString()
                    )

                    player.sendMessage("${ChatColor.GREEN}Your Wealth Gem has lowered the trader's prices by ${VILLAGER_DISCOUNT_PERCENT}%!")
                }
            }
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val clickedItem = event.currentItem
        val cursorItem = event.cursor


        if (isWealthGem(clickedItem) && isBottledOfEnergy(cursorItem)) {
            event.isCancelled = true
    
            if (isWealthGemUnlocked(clickedItem!!)) {
                player.sendMessage("${ChatColor.GOLD}Rich Rush is already unlocked!")
                return
            }
    
            val currentEnergy = getWealthGemEnergy(clickedItem)
            if (currentEnergy < ENERGY_REQUIRED) {
                setWealthGemEnergy(clickedItem, currentEnergy + 1)

                if (cursorItem!!.amount > 1) {
                    cursorItem.amount -= 1
                } else {
                    event.whoClicked.setItemOnCursor(null)
                }
    
                val newEnergy = currentEnergy + 1
                player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f)
                player.sendMessage("${ChatColor.GOLD}Added energy to Wealth Gem! ($newEnergy/$ENERGY_REQUIRED)")
    
                if (newEnergy >= ENERGY_REQUIRED) {
                    unlockWealthGem(clickedItem)
                    player.sendMessage("${ChatColor.GREEN}${ChatColor.BOLD}RICH RUSH UNLOCKED!")
                    player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
                }
    
                updateWealthGemLore(clickedItem)
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
            }
        }
    }
    

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        if (event.damager !is Player) return
        val player = event.damager as Player

        if (!hasWealthGemInInventory(player)) return

        if (event.entity is LivingEntity) {
            val entity = event.entity as LivingEntity
            val equipment = entity.equipment ?: return

            damageEquipment(equipment.helmet)
            damageEquipment(equipment.chestplate)
            damageEquipment(equipment.leggings)
            damageEquipment(equipment.boots)
            damageEquipment(equipment.itemInMainHand)
            damageEquipment(equipment.itemInOffHand)
        }
    }

    private fun damageEquipment(item: ItemStack?) {
        if (item == null || item.type == Material.AIR || item.type.maxDurability.toInt() <= 0) return

        val meta = item.itemMeta ?: return
        if (meta is org.bukkit.inventory.meta.Damageable) {
            val damage = meta.damage + 1
            if (damage < item.type.maxDurability) {
                meta.damage = damage
                item.itemMeta = meta
            }
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (!hasWealthGemInInventory(player)) return

        val block = event.block

        if (isOreBlock(block.type)) {
            val oresMined = player.getMetadata("oresMined").firstOrNull()?.asInt() ?: 0
            player.setMetadata("oresMined", org.bukkit.metadata.FixedMetadataValue(plugin, oresMined + 1))

            if ((oresMined + 1) % 3 == 0) {
                val drops = getOreDrops(block.type)
                if (drops != null) {
                    player.world.dropItemNaturally(block.location, drops)
                    player.world.dropItemNaturally(block.location, drops)
                    player.sendMessage("${ChatColor.GREEN}Your Wealth Gem gives you extra ore!")
                }
            }

            if (richRushActive.contains(player.uniqueId)) {
                val drops = block.getDrops(player.inventory.itemInMainHand)
                for (drop in drops) {
                    player.world.dropItemNaturally(block.location, drop)
                }
            }
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return

        if (richRushActive.contains(killer.uniqueId)) {
            val originalDrops = ArrayList(event.drops)
            event.drops.addAll(originalDrops)
        }
    }

    @EventHandler
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        val player = event.player

        if (!hasWealthGemInInventory(player)) return

        if (event.itemType == Material.NETHERITE_SCRAP) {
            val additionalScrap = ItemStack(Material.NETHERITE_SCRAP, event.itemAmount)
            player.inventory.addItem(additionalScrap)
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        richRushActive.remove(player.uniqueId)

        event.drops.removeIf { isWealthGem(it) }

        for (item in player.inventory.contents) {
            if (item != null && isWealthGem(item)) {
                val lives = getGemLives(item)
                if (lives > 0) {
                    val updatedGem = item.clone()
                    setGemLives(updatedGem, lives - 1)
                    gemsToBeSaved[player.uniqueId] = updatedGem
                    player.sendMessage("${ChatColor.GREEN}Your Wealth Gem lost a life! (${lives - 1}/$MAX_LIVES lives)")
                } else {
                    player.sendMessage("${ChatColor.RED}Your Wealth Gem has been destroyed!")
                    player.banPlayer("${ChatColor.RED}You have lost all your lives! Your gem has been destroyed!")
                }
                break
            }
        }

        val killer = player.killer
        if (killer != null) {
            for (item in killer.inventory.contents) {
                if (item != null && isWealthGem(item)) {
                    val lives = getGemLives(item)
                    if (lives < MAX_LIVES) {
                        setGemLives(item, lives + 1)
                        killer.sendMessage("${ChatColor.GREEN}Your Wealth Gem gained a life! (${lives + 1}/$MAX_LIVES lives)")
                    } else {
                        killer.sendMessage("${ChatColor.YELLOW}Your Wealth Gem is already at maximum lives!")
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
                        player.sendMessage("${ChatColor.GREEN}Your Wealth Gem has returned to you!")
                        gemsToBeSaved.remove(player.uniqueId)
                    }
                }
            }.runTaskLater(plugin, 5L)
        }
    }

    private fun activateUnfortunateAbility(player: Player) {
        if (isGemOnCooldown(player, "unfortunate")) {
            val timeLeft = getRemainingCooldown(player, "unfortunate")
            player.sendMessage("${ChatColor.RED}Unfortunate is on cooldown for ${timeLeft} more seconds!")
            return
        }

        setGemCooldown(player, "unfortunate")


        val nearbyEntities = player.getNearbyEntities(5.0, 5.0, 5.0)
            .filterIsInstance<LivingEntity>()
            .filter { it !is Player || (it as Player).uniqueId != player.uniqueId }

        if (nearbyEntities.isEmpty()) {
            player.sendMessage("${ChatColor.RED}No targets found nearby!")
            return
        }

        player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.8f)
        player.sendMessage("${ChatColor.GREEN}You activate UNFORTUNATE ability!")

        for (entity in nearbyEntities) {
            entity.world.spawnParticle(
                org.bukkit.Particle.DUST,
                entity.location.add(0.0, 1.0, 0.0),
                30,
                0.5,
                1.0,
                0.5,
                0.05,
                org.bukkit.Particle.DustOptions(org.bukkit.Color.GREEN, 1.0f)
            )

            if (entity is Player) {
                entity.sendMessage("${ChatColor.RED}A Wealth Gem's UNFORTUNATE ability has cursed you!")
                applyUnfortunateEffect(entity, 0.33f, 40)
            }
        }
    }

    private fun activateRichRush(player: Player) {
        val gem = player.inventory.contents.find { isWealthGem(it) } ?: return
        if (!isWealthGemUnlocked(gem)) {
            player.sendMessage("${ChatColor.RED}Rich Rush hasn't been unlocked yet!")
            return
        }

        if (isGemOnCooldown(player, "richRush")) {
            val timeLeft = getRemainingCooldown(player, "richRush")
            player.sendMessage("${ChatColor.RED}Rich Rush is on cooldown for ${timeLeft} more seconds!")
            return
        }

        setGemCooldown(player, "richRush")


        player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
        player.sendMessage("${ChatColor.GREEN}${ChatColor.BOLD}You activate RICH RUSH! Double drops for 5 minutes!")

        richRushActive.add(player.uniqueId)

        player.world.spawnParticle(
            org.bukkit.Particle.GLOW,
            player.location,
            50,
            1.0,
            1.0,
            1.0,
            0.5
        )

        object : BukkitRunnable() {
            override fun run() {
                richRushActive.remove(player.uniqueId)
                if (player.isOnline) {
                    player.sendMessage("${ChatColor.YELLOW}Your RICH RUSH ability has ended.")
                }
            }
        }.runTaskLater(plugin, 20L * 60 * 5)
    }

    private fun applyUnfortunateEffect(player: Player, cancelChance: Float, durationSeconds: Int) {
        val taskId = object : BukkitRunnable() {
            override fun run() {
                if (ThreadLocalRandom.current().nextFloat() < cancelChance) {
                    val velocity = player.velocity
                    player.velocity = velocity.setX(0).setZ(0)

                    val event = PlayerInteractEvent(player, org.bukkit.event.block.Action.LEFT_CLICK_AIR,
                        player.inventory.itemInMainHand, null, org.bukkit.block.BlockFace.SELF)
                    event.isCancelled = true

                    player.world.spawnParticle(
                        org.bukkit.Particle.DUST,
                        player.location.add(0.0, 1.0, 0.0),
                        5,
                        0.2,
                        0.2,
                        0.2,
                        0.05
                    )

                    player.playSound(player.location, Sound.BLOCK_STONE_BREAK, 0.3f, 1.0f)
                }
            }
        }.runTaskTimer(plugin, 0L, 5L)

        object : BukkitRunnable() {
            override fun run() {
                taskId.cancel()
                if (player.isOnline) {
                    player.sendMessage("${ChatColor.GREEN}The unfortunate effect has worn off.")
                }
            }
        }.runTaskLater(plugin, 20L * durationSeconds.toLong())
    }

    private fun isToolOrWeapon(item: ItemStack): Boolean {
        val type = item.type
        return type.name.contains("PICKAXE") ||
                type.name.contains("AXE") ||
                type.name.contains("SHOVEL") ||
                type.name.contains("HOE") ||
                type.name.contains("SWORD")
    }

    private fun enchantItem(item: ItemStack) {
        if (!isToolOrWeapon(item)) return

        val meta = item.itemMeta ?: return
        if (item.type.name.contains("SWORD") || item.type.name.contains("SWORD")) {
            if (meta.getEnchantLevel(Enchantment.LOOTING) < 3) {
                meta.addEnchant(Enchantment.LOOTING, 3, true)
            }
        }

        if (item.type.name.contains("PICKAXE") || item.type.name.contains("AXE") ||
            item.type.name.contains("SHOVEL") || item.type.name.contains("HOE")) {
            if (meta.getEnchantLevel(Enchantment.FORTUNE) < 3) {
                meta.addEnchant(Enchantment.FORTUNE, 3, true)
            }
        }

        if (!meta.hasEnchant(Enchantment.MENDING)) {
            meta.addEnchant(Enchantment.MENDING, 1, true)
        }

        item.itemMeta = meta
    }

    private fun isOreBlock(material: Material): Boolean {
        return material.name.contains("_ORE") ||
                material == Material.ANCIENT_DEBRIS
    }

    private fun getOreDrops(material: Material): ItemStack? {
        return when (material) {
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE -> ItemStack(Material.COAL)
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE -> ItemStack(Material.RAW_IRON)
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE -> ItemStack(Material.RAW_GOLD)
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE -> ItemStack(Material.RAW_COPPER)
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE -> ItemStack(Material.REDSTONE)
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE -> ItemStack(Material.EMERALD)
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE -> ItemStack(Material.LAPIS_LAZULI)
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE -> ItemStack(Material.DIAMOND)
            Material.NETHER_GOLD_ORE -> ItemStack(Material.GOLD_NUGGET, 4)
            Material.NETHER_QUARTZ_ORE -> ItemStack(Material.QUARTZ)
            Material.ANCIENT_DEBRIS -> ItemStack(Material.NETHERITE_SCRAP)
            else -> null
        }
    }
}