package io.papermc.blisssmp

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.attribute.Attribute
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.Particle
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.EventPriority
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.PlayerInventory
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.HashMap
import java.util.LinkedList
import java.util.Random
import java.util.UUID
import kotlin.collections.containsKey
import kotlin.collections.minusAssign
import kotlin.compareTo
import kotlin.div
import kotlin.text.compareTo
import kotlin.text.get
import kotlin.text.set

class BlissSMPPlugin : JavaPlugin(), Listener {

    private lateinit var astraGemKey: NamespacedKey
    lateinit var gemLivesKey: NamespacedKey
    private val random = Random()
    private lateinit var strengthGem: StrengthGem
    private lateinit var wealthGem: WealthGem
    private lateinit var puffGem: PuffGem
    private lateinit var fireGem: FireGem
    private lateinit var fluxGem: FluxGem
    private lateinit var speedGem: SpeedGem



    private val DEFAULT_LIVES = 5
    private val MAX_LIVES = 10

    private val capturedMobs = HashMap<UUID, LinkedList<Entity>>()
    private val astralProjections = HashMap<UUID, Entity>()
    private val gemCooldowns = HashMap<UUID, Long>()
    private val possessions = HashMap<UUID, UUID>()
    private val disabledGems = HashMap<UUID, Long>()
    val gemsToBeSaved = HashMap<UUID, ItemStack>()
    private val daggerCooldowns = HashMap<UUID, Long>()
    private val projectionCooldowns = HashMap<UUID, Long>()
    private val consecutiveDaggerCooldowns = HashMap<UUID, Long>()
    private val playerDaggerCounts = HashMap<UUID, Int>()
    private val playerLastDaggerTime = HashMap<UUID, Long>()
    private val DAGGER_SHOTS_MAX = 5
    private val DAGGER_SHOT_WINDOW = 10000L
    private val ASTRAL_ENERGY_REQUIRED = 10
    private lateinit var astralUnlockKey: NamespacedKey
    private lateinit var astralEnergyKey: NamespacedKey
    private val PROJECTION_COOLDOWN = 60000L
    private val DAGGER_COOLDOWN = 20000L

    override fun onEnable() {
        strengthGem = StrengthGem(this)
        wealthGem = WealthGem(this)
        puffGem = PuffGem(this)
        fireGem = FireGem(this)
        fluxGem = FluxGem(this)
        speedGem = SpeedGem(this)

        val trader = Trader(this)
        trader.registerTraderRecipe()
        trader.registerEvents()

        val repairKit = RepairKit(this)
        repairKit.registerRepairKitRecipe()
        repairKit.registerEvents()

        logger.info("BlissSMP Plugin has been enabled!")
        logger.info("Registered Trader and RepairKit recipes!")

        getCommand("bliss")?.setExecutor(BlissGiveCommand(this))

        server.pluginManager.registerEvents(this, this)
        server.pluginManager.registerEvents(BottledOfEnergy(this), this)

        astraGemKey = NamespacedKey(this, "astra_gem")
        gemLivesKey = NamespacedKey(this, "gem_lives")
        astralUnlockKey = NamespacedKey(this, "astral_unlock")
        astralEnergyKey = NamespacedKey(this, "astral_energy")

        startGemStatusChecker()
    }
    @EventHandler
    fun onPlayerJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
        val player = event.player
        if (player.hasPlayedBefore()) return

        val gems = listOf(
            { strengthGem.createStrengthGem() },
            { wealthGem.createWealthGem() },
            { puffGem.createPuffGem() },
            { fireGem.createFireGem() },
            { fluxGem.createFluxGem() },
            { speedGem.createSpeedGem() },
            { createAstraGem() } 
        )

        val randomGem = gems[random.nextInt(gems.size)]()
        player.inventory.addItem(randomGem)
        player.sendMessage("${ChatColor.GOLD}You have received a random magical gem to begin your journey!")
    }
    fun getWealthGem(): WealthGem {
        return wealthGem
    }
    fun getPuffGem(): PuffGem {
        return puffGem
    }
    fun getFireGem(): FireGem {
        return fireGem
    }
    fun getFluxGem(): FluxGem {
        return fluxGem
    }
    fun getSpeedGem(): SpeedGem {
        return speedGem
    }

    override fun onDisable() {
        logger.info("BlissSMP Plugin has been disabled!")

        astralProjections.forEach { (uuid, entity) ->
            val player = Bukkit.getPlayer(uuid)
            if (player != null) {
                endAstralProjection(player)
            } else {
                entity.remove()
            }
        }

        possessions.forEach { (possessorUuid, _) ->
            val possessor = Bukkit.getPlayer(possessorUuid)
            if (possessor != null) {
                endPossession(possessor)
            }
        }
    }

    fun getStrengthGem(): StrengthGem {
        return strengthGem
    }

    private fun startGemStatusChecker() {
        object : BukkitRunnable() {
            override fun run() {
                val currentTime = System.currentTimeMillis()

                val expiredDisabled = disabledGems.filter { currentTime >= it.value }
                expiredDisabled.forEach { (uuid, _) ->
                    disabledGems.remove(uuid)
                    val player = Bukkit.getPlayer(uuid)
                }
            }
        }.runTaskTimer(this, 20L, 20L)
    }

    fun isAstraGem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.AMETHYST_SHARD) return false

        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(astraGemKey, PersistentDataType.BYTE)
    }

    private fun getGemLives(item: ItemStack): Int {
        if (!isAstraGem(item)) return 0
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
    }

    private fun setGemLives(item: ItemStack, lives: Int): ItemStack {
        if (!isAstraGem(item)) return item

        val meta = item.itemMeta ?: return item
        val adjustedLives = lives.coerceIn(0, MAX_LIVES)
        meta.persistentDataContainer.set(gemLivesKey, PersistentDataType.INTEGER, adjustedLives)

        val lore = meta.lore ?: mutableListOf()
        if (lore.isNotEmpty() && lore.size > 2) {
            lore[2] = "${ChatColor.LIGHT_PURPLE}Lives: ${ChatColor.WHITE}$adjustedLives/$MAX_LIVES"
        }
        meta.lore = lore

        item.itemMeta = meta
        return item
    }

    fun isGemOnCooldown(player: Player): Boolean {
        val cooldownEnd = gemCooldowns[player.uniqueId] ?: return false
        return System.currentTimeMillis() < cooldownEnd

    }

    fun isGemDisabled(player: Player): Boolean {
        return disabledGems.containsKey(player.uniqueId)
    }

    fun disableGem(player: Player, seconds: Int) {
        val disableUntil = System.currentTimeMillis() + (seconds * 1000)
        disabledGems[player.uniqueId] = disableUntil
        player.sendMessage("${ChatColor.RED}Your Astra Gem has been disabled for $seconds seconds!")
    }

    fun createAstraGem(): ItemStack {
        val astraGem = ItemStack(Material.AMETHYST_SHARD)
        val meta = astraGem.itemMeta

        if (meta != null) {
            meta.setDisplayName("${ChatColor.LIGHT_PURPLE}${ChatColor.BOLD}Astra Gem")
            meta.lore = listOf(
                "${ChatColor.GRAY}A mystical gem that connects to its wielder's soul.",
                "",
                "${ChatColor.DARK_PURPLE}Lives: ${ChatColor.WHITE}$DEFAULT_LIVES/$MAX_LIVES",
                "${ChatColor.DARK_PURPLE}Astral Projection: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ASTRAL_ENERGY_REQUIRED Energy)",
                "",
                "${ChatColor.DARK_PURPLE}Passives:",
                "${ChatColor.WHITE}• PRISTINE: 15% of attacks will phase through you",
                "${ChatColor.WHITE}• Soul Absorption: Heal 2.5❤ from mobs, 5❤ from players",
                "${ChatColor.WHITE}• Capture 2 mobs inside your gem",
                "",
                "${ChatColor.DARK_PURPLE}Powers:",
                "${ChatColor.WHITE}• DAGGERS: Shoot 5 disabling projectiles (Right-Click, one at a time)",
                "${ChatColor.WHITE}• ASTRAL PROJECTION: Explore as a spirit (Shift+Right-Click) [Needs to be unlocked]",
                "",
                "${ChatColor.YELLOW}• Kill players to gain lives (Max: ${MAX_LIVES})",
                "${ChatColor.YELLOW}• Drag Bottled Of Energy onto gem to unlock Astral Projection",
                "${ChatColor.RED}• 0 Lives = Server Ban"
            )
            meta.isUnbreakable = true
            meta.addEnchant(Enchantment.UNBREAKING, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE)

            meta.persistentDataContainer.set(astraGemKey, PersistentDataType.BYTE, 1)
            meta.persistentDataContainer.set(gemLivesKey, PersistentDataType.INTEGER, DEFAULT_LIVES)
            meta.persistentDataContainer.set(astralUnlockKey, PersistentDataType.BYTE, 0)
            meta.persistentDataContainer.set(astralEnergyKey, PersistentDataType.INTEGER, 0)

            astraGem.itemMeta = meta
        }

        return astraGem
    }

    private fun isAstralUnlocked(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.getOrDefault(astralUnlockKey, PersistentDataType.BYTE, 0) == 1.toByte()
    }
    private fun getAstralEnergy(item: ItemStack): Int {
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(astralEnergyKey, PersistentDataType.INTEGER, 0)
    }
    private fun setAstralEnergy(item: ItemStack, count: Int) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(astralEnergyKey, PersistentDataType.INTEGER, count)
        item.itemMeta = meta
    }
    private fun unlockAstral(item: ItemStack) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(astralUnlockKey, PersistentDataType.BYTE, 1)
        val lore = meta.lore?.toMutableList() ?: return
        for (i in lore.indices) {
            if (lore[i].contains("Astral Projection:")) {
                lore[i] = "${ChatColor.DARK_PURPLE}Astral Projection: ${ChatColor.GREEN}Unlocked"
            }
        }
        meta.lore = lore
        item.itemMeta = meta
    }
    private fun updateAstraGemLore(item: ItemStack) {
        val meta = item.itemMeta ?: return
        val lore = meta.lore?.toMutableList() ?: return
        val energy = getAstralEnergy(item)
        val unlocked = isAstralUnlocked(item)
        for (i in lore.indices) {
            if (lore[i].contains("Astral Projection:")) {
                lore[i] = if (unlocked) {
                    "${ChatColor.DARK_PURPLE}Astral Projection: ${ChatColor.GREEN}Unlocked"
                } else {
                    "${ChatColor.DARK_PURPLE}Astral Projection: ${ChatColor.RED}Locked${ChatColor.WHITE} (Requires $ASTRAL_ENERGY_REQUIRED Energy) ${ChatColor.YELLOW}[$energy/$ASTRAL_ENERGY_REQUIRED]"
                }
            }
        }
        meta.lore = lore
        item.itemMeta = meta
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val item = event.oldCursor
        val player = event.whoClicked as? Player ?: return

        if (isAnyGem(item)) {
            val playerInvSlots = 0..35
            val offhandSlot = 40

            if (event.rawSlots.any { it !in playerInvSlots && it != offhandSlot }) {
                event.isCancelled = true
                player.sendMessage("${ChatColor.RED}Magical gems can only be stored in your inventory!")
            }
        }
    }

    private fun isAnyGem(item: ItemStack?): Boolean {
        if (item == null) return false
        return isAstraGem(item) ||
                strengthGem.isStrengthGem(item) ||
                wealthGem.isWealthGem(item) ||
                puffGem.isPuffGem(item) ||
                fireGem.isFireGem(item) ||
                fluxGem.isFluxGem(item) ||
                speedGem.isSpeedGem(item)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val item = event.currentItem
        val cursor = event.cursor

        if (isAstraGem(item) && isBottledOfEnergy(cursor)) {
            event.isCancelled = true
            handleAstraGemEnergy(player, item!!, cursor!!)
            return
        }

        if (isAnyGem(item) || isAnyGem(cursor)) {
            val clickedInvIsPlayer = event.clickedInventory is PlayerInventory
            val destInvIsPlayer = event.view.topInventory is PlayerInventory

            if (!clickedInvIsPlayer && !destInvIsPlayer) {
                event.isCancelled = true
                player.sendMessage("${ChatColor.RED}Magical gems can only be stored in your inventory!")
                return
            }
        }
    }

    private fun handleAstraGemEnergy(player: Player, clickedItem: ItemStack, cursorItem: ItemStack) {
        if (isAstralUnlocked(clickedItem)) {
            player.sendMessage("${ChatColor.YELLOW}Astral Projection is already unlocked!")
            return
        }

        val currentEnergy = getAstralEnergy(clickedItem)
        if (currentEnergy < ASTRAL_ENERGY_REQUIRED) {
            setAstralEnergy(clickedItem, currentEnergy + 1)
            cursorItem.amount -= 1
            val newEnergy = currentEnergy + 1
            player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
            player.sendMessage("${ChatColor.AQUA}Added energy to Astra Gem! (${newEnergy}/$ASTRAL_ENERGY_REQUIRED)")
            if (newEnergy >= ASTRAL_ENERGY_REQUIRED) {
                unlockAstral(clickedItem)
                player.sendMessage("${ChatColor.GREEN}${ChatColor.BOLD}ASTRAL PROJECTION UNLOCKED!")
                player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.5f)
            }
            updateAstraGemLore(clickedItem)
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onItemDrop(event: PlayerDropItemEvent) {
        val item = event.itemDrop.itemStack
        if (isAnyGem(item)) {
            event.isCancelled = true
            event.player.sendMessage("${ChatColor.RED}You cannot drop magical gems!")
        }
    }
    private fun isBottledOfEnergy(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.EXPERIENCE_BOTTLE) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(
            NamespacedKey(this, "energy_bottle"),
            PersistentDataType.BYTE
        )
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity

        for (item in player.inventory.contents) {
            if (item != null && isAstraGem(item)) {
                val gem = item.clone()
                val lives = getGemLives(gem)

                if (lives > 0) {
                    val updatedGem = setGemLives(gem, lives - 1)
                    gemsToBeSaved[player.uniqueId] = updatedGem
                    player.sendMessage("${ChatColor.LIGHT_PURPLE}Your Astra Gem lost a life! (${lives - 1}/$MAX_LIVES lives remaining)")

                    event.drops.removeIf { isAstraGem(it) }
                } else {
                    player.sendMessage("${ChatColor.LIGHT_PURPLE}Your Astra Gem has been destroyed! (0 lives remaining)")
                    player.banPlayer("${ChatColor.RED}You have lost all your lives! Your gem has been destroyed!")
                }
                break
            }
        }

        val killer = player.killer
        if (killer != null) {
            for (item in killer.inventory.contents) {
                if (item != null && isAstraGem(item)) {
                    val lives = getGemLives(item)
                    if (lives < MAX_LIVES) {
                        setGemLives(item, lives + 1)
                        killer.sendMessage("${ChatColor.LIGHT_PURPLE}Your Astra Gem gained a life! (${lives + 1}/$MAX_LIVES lives)")
                    } else {
                        killer.sendMessage("${ChatColor.YELLOW}Your Astra Gem is already at maximum lives! ($MAX_LIVES/$MAX_LIVES lives)")
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
                        gemsToBeSaved.remove(player.uniqueId)
                    }
                }
            }.runTaskLater(this, 5L)
        }
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val target = event.entity

        if (damager !is Player || target !is Player) return
        if (!astralProjections.containsKey(damager.uniqueId)) return

        event.isCancelled = true
        target.damage(4.0, damager)
        target.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 60, 0))
        target.addPotionEffect(PotionEffect(PotionEffectType.NAUSEA, 100, 0))

        target.world.playSound(target.location, Sound.ENTITY_GHAST_SCREAM, 0.5f, 1.5f)
        target.world.spawnParticle(Particle.SMOKE, target.location.add(0.0, 1.0, 0.0), 20, 0.5, 1.0, 0.5, 0.05)

        target.sendMessage("${ChatColor.DARK_PURPLE}${ChatColor.ITALIC}An astral being attacks you!")
        damager.sendMessage("${ChatColor.LIGHT_PURPLE}You spook ${target.name}!")
    }



    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val killer = entity.killer

        if (killer != null && killer is Player) {
            val mainHand = killer.inventory.itemInMainHand
            val offHand = killer.inventory.itemInOffHand


            if ((isAstraGem(mainHand) || isAstraGem(offHand)) && !isGemDisabled(killer)) {

                var healAmount = 5.0
                if (entity is Player) {
                    healAmount = 10.0
                }

                val maxHealth = killer.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
                val newHealth = Math.min(killer.health + healAmount, maxHealth)
                killer.health = newHealth

                killer.world.playSound(killer.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f)
                killer.sendMessage("${ChatColor.LIGHT_PURPLE}Your Astra Gem absorbed a soul, healing you!")
            }
        }
    }


    @EventHandler
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        val entity = event.rightClicked

        if (entity !is LivingEntity || entity is Player) return

        val item = player.inventory.itemInMainHand

        if (isAstraGem(item) && !isGemDisabled(player)) {

            if (astralProjections.containsKey(player.uniqueId)) return

            event.isCancelled = true

            if (!capturedMobs.containsKey(player.uniqueId)) {
                capturedMobs[player.uniqueId] = LinkedList()
            }

            val capturedList = capturedMobs[player.uniqueId]!!

            if (capturedList.size >= 2) {
                player.sendMessage("${ChatColor.RED}Your Astra Gem can only hold 2 mobs at a time!")
                return
            }

            capturedList.add(entity)
            player.world.playSound(entity.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.5f)
            player.sendMessage("${ChatColor.LIGHT_PURPLE}You captured a ${entity.type.name.lowercase().replace('_', ' ')} in your Astra Gem!")

            entity.world.spawnParticle(org.bukkit.Particle.PORTAL, entity.location, 50, 0.5, 1.0, 0.5, 0.1)
            entity.remove()
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return
    
        if (!isAstraGem(item) || isGemDisabled(player)) return
    
        val action = event.action
    
        if (action.name.contains("RIGHT_CLICK") && player.isSneaking) {
            event.isCancelled = true
    
            if (!isAstralUnlocked(item)) {
                player.sendMessage("${ChatColor.RED}Astral Projection is locked! Add $ASTRAL_ENERGY_REQUIRED Bottled Of Energy to unlock.")
                return
            }
    
            startAstralProjection(player)
            return
        }
    
        if (action.name.contains("RIGHT_CLICK") && !player.isSneaking) {
            event.isCancelled = true
            shootDaggers(player)
            return
        }
    
        if (action.name.contains("LEFT_CLICK")) {
            val capturedList = capturedMobs[player.uniqueId] ?: return
    
            if (capturedList.isEmpty()) {
                player.sendMessage("${ChatColor.RED}You don't have any mobs captured in your Astra Gem!")
                return
            }
    
            val releasedMob = capturedList.removeLast()
            val entityType = releasedMob.type
    
            val spawnLocation = player.location.add(player.location.direction.multiply(2))
            val spawnedEntity = player.world.spawnEntity(spawnLocation, entityType)
    
            player.world.playSound(spawnedEntity.location, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 1.5f)
            player.sendMessage("${ChatColor.LIGHT_PURPLE}You released a ${entityType.name.lowercase().replace('_', ' ')} from your Astra Gem!")
    
            spawnedEntity.world.spawnParticle(
                org.bukkit.Particle.REVERSE_PORTAL,
                spawnedEntity.location,
                50,
                0.5,
                1.0,
                0.5,
                0.1
            )
        }
    }

    private fun isDaggerOnCooldown(player: Player): Boolean {
        val cooldownEnd = daggerCooldowns[player.uniqueId] ?: return false
        return System.currentTimeMillis() < cooldownEnd
    }

    private fun isProjectionOnCooldown(player: Player): Boolean {
        val cooldownEnd = projectionCooldowns[player.uniqueId] ?: return false
        return System.currentTimeMillis() < cooldownEnd
    }

    private fun shootDaggers(player: Player) {
        shootDaggerConsecutively(player)
    }

    private fun shootDaggerConsecutively(player: Player) {
        if (isGemOnCooldown(player)) {
            val timeLeft = (gemCooldowns[player.uniqueId]!! - System.currentTimeMillis()) / 1000
            player.sendMessage("${ChatColor.RED}Your Astra Gem is on cooldown for ${timeLeft} more seconds!")
            return
        }

        val now = System.currentTimeMillis()
        val count = playerDaggerCounts.getOrDefault(player.uniqueId, 0)
        val lastTime = playerLastDaggerTime.getOrDefault(player.uniqueId, 0L)

        if (count == 0 || now - lastTime > DAGGER_SHOT_WINDOW) {
            playerDaggerCounts[player.uniqueId] = 0
        }

        val newCount = playerDaggerCounts.getOrDefault(player.uniqueId, 0) + 1
        playerDaggerCounts[player.uniqueId] = newCount
        playerLastDaggerTime[player.uniqueId] = now

        val direction = player.location.direction
        val projectile = player.launchProjectile(org.bukkit.entity.Snowball::class.java, direction.multiply(2.0))
        projectile.customName = "AstralDagger"
        projectile.isCustomNameVisible = false
        projectile.setMetadata("isAstraDagger", org.bukkit.metadata.FixedMetadataValue(this, true))
        projectile.setGravity(false)
        projectile.velocity = direction.multiply(2.0)
        projectile.item = ItemStack(Material.AMETHYST_SHARD)

        player.world.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.0f, 2.0f)
        player.sendMessage("${ChatColor.LIGHT_PURPLE}You unleash an astral dagger! (${newCount}/$DAGGER_SHOTS_MAX)")

        if (newCount >= DAGGER_SHOTS_MAX) {
            gemCooldowns[player.uniqueId] = System.currentTimeMillis() + 20000
            playerDaggerCounts[player.uniqueId] = 0
            player.sendMessage("${ChatColor.LIGHT_PURPLE}Your Astra Gem daggers are now on cooldown!")
        }
    }


    @EventHandler
    fun onDaggerHit(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager is org.bukkit.entity.ShulkerBullet && damager.customName == "AstralDagger" &&
            damager.hasMetadata("isAstraDagger")) {
            if (event.entity is Player || event.entity is LivingEntity) {
                event.damage = 4.0
                event.isCancelled = false
                if (event.entity is Player) {
                    val player = event.entity as Player
                    player.noDamageTicks = 0
                }
                if (event.entity is Player) {
                    val player = event.entity as Player
                    disableGem(player, 10)
                    player.world.playSound(player.location, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f)
                    player.world.spawnParticle(
                        org.bukkit.Particle.DRAGON_BREATH,
                        player.location.add(0.0, 1.0, 0.0),
                        30,
                        0.5,
                        1.0,
                        0.5,
                        0.05
                    )
                }
            }
        }
    }

    private fun startAstralProjection(player: Player) {
        if (astralProjections.containsKey(player.uniqueId)) {
            player.sendMessage("${ChatColor.RED}You are already in astral projection!")
            return
        }

        if (isProjectionOnCooldown(player)) {
            val timeLeft = (projectionCooldowns[player.uniqueId]!! - System.currentTimeMillis()) / 1000
            player.sendMessage("${ChatColor.RED}Astral Projection is on cooldown for $timeLeft more seconds!")
            return
        }

        if (isGemOnCooldown(player)) {
            val timeLeft = (gemCooldowns[player.uniqueId]!! - System.currentTimeMillis()) / 1000
            player.sendMessage("${ChatColor.RED}Your Astra Gem is on cooldown for $timeLeft more seconds!")
            return
        }

        val npc = player.world.spawnEntity(player.location, EntityType.ARMOR_STAND) as org.bukkit.entity.ArmorStand
        npc.isVisible = false
        npc.setGravity(false)
        npc.customName = "AstralBody_${player.uniqueId}"
        npc.isCustomNameVisible = false

        astralProjections[player.uniqueId] = npc

        player.isInvisible = true
        player.allowFlight = true
        player.isFlying = true
        player.addPotionEffect(PotionEffect(PotionEffectType.NIGHT_VISION, 100000, 0, false, false))

        player.world.playSound(player.location, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.5f)
        player.world.spawnParticle(
            Particle.DRAGON_BREATH,
            player.location.add(0.0, 1.0, 0.0),
            100,
            0.5,
            1.0,
            0.5,
            0.05
        )

        player.sendMessage("${ChatColor.LIGHT_PURPLE}You enter astral projection mode!")
        player.sendMessage("${ChatColor.GRAY}Left-click near a player to spook them.")
        player.sendMessage("${ChatColor.GRAY}Right-click a player to possess them.")
        player.sendMessage("${ChatColor.GRAY}Click with an empty hand to return to your body.")

        object : BukkitRunnable() {
            override fun run() {
                if (!astralProjections.containsKey(player.uniqueId)) {
                    cancel()
                    return
                }

                val distance = player.location.distance(npc.location)
                if (distance > 150) {
                    player.sendMessage("${ChatColor.RED}You've gone too far from your body!")
                    endAstralProjection(player)
                    cancel()
                    return
                }

                player.world.spawnParticle(
                    Particle.DRAGON_BREATH,
                    player.location.add(0.0, 0.5, 0.0),
                    1,
                    0.1,
                    0.1,
                    0.1,
                    0.01
                )
            }
        }.runTaskTimer(this, 20L, 5L)

        projectionCooldowns[player.uniqueId] = System.currentTimeMillis() + PROJECTION_COOLDOWN
    }


    private fun endAstralProjection(player: Player) {
        if (!astralProjections.containsKey(player.uniqueId)) return

        val npc = astralProjections[player.uniqueId]!!

        if (possessions.containsKey(player.uniqueId)) {
            endPossession(player)
        }

        player.teleport(npc.location)

        player.isInvisible = false
        player.allowFlight = player.gameMode.name.equals("CREATIVE", ignoreCase = true) ||
                player.gameMode.name.equals("SPECTATOR", ignoreCase = true)
        if (!player.allowFlight) {
            player.isFlying = false
        }
        player.removePotionEffect(PotionEffectType.NIGHT_VISION)

        player.world.playSound(player.location, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.5f)
        player.world.spawnParticle(
            org.bukkit.Particle.DRAGON_BREATH,
            player.location.add(0.0, 1.0, 0.0),
            50,
            0.5,
            1.0,
            0.5,
            0.05
        )

        npc.remove()

        astralProjections.remove(player.uniqueId)
        player.sendMessage("${ChatColor.LIGHT_PURPLE}You return to your physical body.")
    }

    @EventHandler
    fun onAstralInteract(event: PlayerInteractEntityEvent) {
        val player = event.player

        if (!astralProjections.containsKey(player.uniqueId)) return

        val target = event.rightClicked
        if (target !is Player) return

        startPossession(player, target)
        event.isCancelled = true
    }

    private fun startPossession(possessor: Player, possessed: Player) {
        if (possessions.containsKey(possessor.uniqueId)) {
            possessor.sendMessage("${ChatColor.RED}You are already possessing someone!")
            return
        }

        possessions[possessor.uniqueId] = possessed.uniqueId

        possessor.teleport(possessed.location)

        possessed.world.playSound(possessed.location, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.5f, 1.5f)
        possessed.sendMessage("${ChatColor.DARK_PURPLE}${ChatColor.ITALIC}You feel a cold presence surrounding you...")

        possessor.sendMessage("${ChatColor.LIGHT_PURPLE}You possess ${possessed.name}!")
        possessor.sendMessage("${ChatColor.GRAY}Sneak or wait for them to take damage to exit.")

        object : BukkitRunnable() {
            override fun run() {
                if (!possessions.containsKey(possessor.uniqueId)) {
                    cancel()
                    return
                }

                possessor.teleport(possessed.location)
            }
        }.runTaskTimer(this, 1L, 1L)
    }

    private fun endPossession(possessor: Player) {
        if (!possessions.containsKey(possessor.uniqueId)) return

        val possessedUuid = possessions[possessor.uniqueId]!!
        val possessed = Bukkit.getPlayer(possessedUuid)

        possessions.remove(possessor.uniqueId)

        if (possessed != null) {
            disableGem(possessed, 10)
            possessed.sendMessage("${ChatColor.DARK_PURPLE}A spirit has left your body, leaving you feeling drained!")
            possessed.world.playSound(possessed.location, Sound.ENTITY_VEX_HURT, 1.0f, 0.5f)
        }

        possessor.sendMessage("${ChatColor.LIGHT_PURPLE}You exit the possession.")
    }

    @EventHandler
    fun onPossessedDamage(event: EntityDamageEvent) {
        if (event.entity !is Player) return

        val player = event.entity as Player

        val possessor = possessions.entries.find { it.value == player.uniqueId }?.key
        if (possessor != null) {
            val possessorPlayer = Bukkit.getPlayer(possessor)
            if (possessorPlayer != null) {
                endPossession(possessorPlayer)
            }
        }
    }

    @EventHandler
    fun onPossessorSneak(event: PlayerToggleSneakEvent) {
        val player = event.player

        if (event.isSneaking && possessions.containsKey(player.uniqueId)) {
            endPossession(player)
        }
    }

    @EventHandler
    fun onAstralBodyDamage(event: EntityDamageEvent) {
        val entity = event.entity

        if (entity.customName?.startsWith("AstralBody_") == true) {
            val playerUuid = UUID.fromString(entity.customName!!.substring(11))
            val player = Bukkit.getPlayer(playerUuid)

            if (player != null) {
                endAstralProjection(player)
                player.sendMessage("${ChatColor.RED}Your astral body was damaged, forcing you to return!")
            }

            event.isCancelled = true
        }
    }

    @EventHandler
    fun onEmptyHandClick(event: PlayerInteractEvent) {
        val player = event.player

        if (!astralProjections.containsKey(player.uniqueId)) return

        if (player.inventory.itemInMainHand.type == Material.AIR &&
            (event.action.name.contains("LEFT_CLICK") || event.action.name.contains("RIGHT_CLICK"))) {
            endAstralProjection(player)
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (isTraderItem(item)) {
            event.isCancelled = true
            event.player.sendMessage("${ChatColor.RED}Trader items cannot be placed as blocks!")
        }
    }

    private fun isTraderItem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.CONDUIT) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(NamespacedKey(this, "trader_item"), PersistentDataType.BYTE)
    }
}