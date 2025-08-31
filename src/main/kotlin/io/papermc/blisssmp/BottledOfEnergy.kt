package io.papermc.blisssmp

import org.bukkit.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable

class BottledOfEnergy(private val plugin: JavaPlugin) : Listener {

    private val ENERGY_BOTTLE_KEY = NamespacedKey(plugin, "energy_bottle")

    companion object {
        fun createItem(pluginInstance: JavaPlugin): ItemStack {
            val bottle = ItemStack(Material.EXPERIENCE_BOTTLE)
            val meta = bottle.itemMeta!!

            meta.setDisplayName("${ChatColor.LIGHT_PURPLE}§l✧ ${ChatColor.DARK_PURPLE}§lBottled Of Energy ${ChatColor.LIGHT_PURPLE}§l✧")
            meta.lore = listOf(
                "${ChatColor.DARK_GRAY}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
                "${ChatColor.ITALIC}${ChatColor.LIGHT_PURPLE}\"The soul never truly dies...\"",
                " ",
                "${ChatColor.DARK_PURPLE}§oCondensed essence of a fallen soul,",
                "${ChatColor.DARK_PURPLE}§ohumming with arcane power.",
                " ",
                "${ChatColor.YELLOW}✦ ${ChatColor.WHITE}Combine with magical gems to",
                "${ChatColor.WHITE}  unlock their hidden potential.",
                "${ChatColor.YELLOW}✦ ${ChatColor.WHITE}Maximum stack: 5",
                "${ChatColor.DARK_GRAY}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            )

            try {
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)

                try {
                    meta.addItemFlags(ItemFlag.HIDE_DYE)
                } catch (e: Exception) {
                }
            } catch (e: Exception) {
            }

            meta.persistentDataContainer.set(
                NamespacedKey(pluginInstance, "energy_bottle"),
                PersistentDataType.BYTE,
                1
            )

            bottle.itemMeta = meta
            return bottle
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val location = player.location
        val world = player.world

        createDeathEffect(location)

        val energyBottle = createItem(plugin)
        world.dropItemNaturally(location, energyBottle)

        location.getNearbyPlayers(30.0).forEach { nearbyPlayer ->
            nearbyPlayer.sendMessage("${ChatColor.DARK_PURPLE}${ChatColor.ITALIC}A soul has been captured in a bottle of energy...")
            nearbyPlayer.playSound(nearbyPlayer.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f)
        }
    }

    private fun createDeathEffect(location: Location) {
        object : BukkitRunnable() {
            private var ticks = 0
            override fun run() {
                if (ticks >= 20) {
                    this.cancel()
                    return
                }

                for (i in 0 until 8) {
                    val angle = (ticks * 18 + i * 45) * Math.PI / 180
                    val x = Math.cos(angle) * ticks / 10.0
                    val z = Math.sin(angle) * ticks / 10.0
                    val particleLoc = location.clone().add(x, ticks / 10.0, z)

                    location.world.spawnParticle(
                        Particle.DRAGON_BREATH,
                        particleLoc,
                        1, 0.0, 0.0, 0.0, 0.01
                    )
                }

                if (ticks == 0) {
                    location.world.playSound(location, Sound.ENTITY_WITHER_DEATH, 0.3f, 1.5f)
                }

                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val cursor = event.cursor ?: return
        val current = event.currentItem ?: return

        handleStackingLimit(event, cursor, current)
    }

    private fun handleStackingLimit(event: InventoryClickEvent, cursor: ItemStack, current: ItemStack) {
        if (isEnergyBottle(cursor)) {
            if (cursor.amount > 5) {
                cursor.amount = 5
            }

            if (isEnergyBottle(current) &&
                (event.action == InventoryAction.PLACE_ALL || event.action == InventoryAction.PLACE_SOME)) {

                val totalAmount = current.amount + cursor.amount
                if (totalAmount > 5) {
                    event.isCancelled = true

                    val transferAmount = 5 - current.amount
                    if (transferAmount > 0) {
                        current.amount = 5
                        cursor.amount -= transferAmount
                        if (cursor.amount <= 0) {
                            event.whoClicked.setItemOnCursor(null)
                        }
                    }
                }
            }
        }
    }

    private fun isEnergyBottle(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.EXPERIENCE_BOTTLE) return false
        val meta = item.itemMeta ?: return false

        return meta.persistentDataContainer.has(
            NamespacedKey(plugin, "energy_bottle"),
            PersistentDataType.BYTE
        )
    }

    fun register() {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }
}