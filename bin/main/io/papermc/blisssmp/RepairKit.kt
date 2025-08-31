package io.papermc.blisssmp

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.entity.Player

class RepairKit(private val plugin: JavaPlugin) : Listener {

    private val repairKitKey = NamespacedKey(plugin, "repair_kit")
    private val recipeKey = NamespacedKey(plugin, "repair_kit_recipe")
    private val gemKeys = listOf(
        "astra_gem", "strength_gem", "wealth_gem",
        "puff_gem", "fire_gem", "flux_gem", "speed_gem"
    ).map { NamespacedKey(plugin, it) }
    private val gemLivesKey = NamespacedKey(plugin, "gem_lives")
    private val playerLivesKey = NamespacedKey(plugin, "player_lives")

    fun createRepairKit(): ItemStack {
        val item = ItemStack(Material.GRINDSTONE)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName("${ChatColor.AQUA}${ChatColor.BOLD}Repair Kit")
            meta.lore = listOf(
                "${ChatColor.GRAY}A powerful kit capable of repairing",
                "${ChatColor.GRAY}even the most damaged artifacts.",
                "",
                "${ChatColor.YELLOW}Use wisely!"
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            meta.persistentDataContainer.set(repairKitKey, org.bukkit.persistence.PersistentDataType.BYTE, 1)
            item.itemMeta = meta
        }
        return item
    }

    fun registerRepairKitRecipe() {
        val repairKit = createRepairKit()
        val recipe = ShapedRecipe(recipeKey, repairKit)
        recipe.shape(
            "DND",
            "NWN",
            "DND"
        )
        recipe.setIngredient('D', Material.DIAMOND_BLOCK)
        recipe.setIngredient('N', Material.NETHERITE_BLOCK)
        recipe.setIngredient('W', Material.WITHER_SKELETON_SKULL)
        Bukkit.addRecipe(recipe)
    }

    fun registerEvents() {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        event.player.discoverRecipe(recipeKey)
    }

    @EventHandler
    fun onPlayerUseRepairKit(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return

        if (!(event.action.isRightClick) || !isRepairKit(item)) return

        val inventory = player.inventory
        var repaired = false
        for (slot in inventory.contents.indices) {
            val stack = inventory.getItem(slot) ?: continue
            if (isGem(stack)) {
                val meta = stack.itemMeta ?: continue

                var lives = 5
                var usedKey: NamespacedKey? = null
                if (meta.persistentDataContainer.has(gemLivesKey, PersistentDataType.INTEGER)) {
                    lives = meta.persistentDataContainer.getOrDefault(gemLivesKey, PersistentDataType.INTEGER, 5)
                    usedKey = gemLivesKey
                } else if (meta.persistentDataContainer.has(playerLivesKey, PersistentDataType.INTEGER)) {
                    lives = meta.persistentDataContainer.getOrDefault(playerLivesKey, PersistentDataType.INTEGER, 5)
                    usedKey = playerLivesKey
                }

                if (lives < 5 && usedKey != null) {
                    meta.persistentDataContainer.set(usedKey, PersistentDataType.INTEGER, 5)
                    val lore = meta.lore?.toMutableList()
                    if (lore != null) {
                        for (i in lore.indices) {
                            if (lore[i].contains("Lives:", ignoreCase = true)) {
                                val regex = Regex("""(\d+)\s*/\s*(\d+)""")
                                lore[i] = regex.replace(lore[i]) { m ->
                                    "5/${m.groupValues[2]}"
                                }
                            }
                        }
                        meta.lore = lore
                    }
                    stack.itemMeta = meta
                    repaired = true
                }
            }
        }

        if (repaired) {
            item.amount = item.amount - 1
            player.sendMessage("${ChatColor.AQUA}All your gems with less than 5 lives have been restored to 5 lives!")
        } else {
            player.sendMessage("${ChatColor.YELLOW}No gems needed repairing.")
        }
        event.isCancelled = true
    }

    private fun isRepairKit(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.GRINDSTONE) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(repairKitKey, PersistentDataType.BYTE)
    }

    private fun isGem(item: ItemStack?): Boolean {
        if (item == null) return false
        val meta = item.itemMeta ?: return false
        return gemKeys.any { meta.persistentDataContainer.has(it, PersistentDataType.BYTE) || meta.persistentDataContainer.has(it, PersistentDataType.STRING) }
    }
}