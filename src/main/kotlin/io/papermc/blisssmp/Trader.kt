package io.papermc.blisssmp

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

class Trader(private val plugin: JavaPlugin) : Listener {
    private val traderKey = NamespacedKey(plugin, "trader_item")
    private val recipeKey = NamespacedKey(plugin, "trader_recipe")

    private val gemKeys = listOf(
        "astra_gem", "strength_gem", "wealth_gem",
        "puff_gem", "fire_gem", "flux_gem", "speed_gem"
    ).map { NamespacedKey(plugin, it) }

    fun createTraderItem(): ItemStack {
        return ItemStack(Material.CONDUIT).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("${ChatColor.GOLD}${ChatColor.BOLD}Trader")
                lore = listOf(
                    "${ChatColor.GRAY}A mysterious artifact coveted by merchants.",
                    "${ChatColor.DARK_PURPLE}It radiates with the power of commerce.",
                    "",
                    "${ChatColor.YELLOW}Right-click to trade your current gem for a new one!"
                )
                addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
                persistentDataContainer.set(traderKey, PersistentDataType.BYTE, 1)
            }
        }
    }

    fun registerTraderRecipe() {
        ShapedRecipe(recipeKey, createTraderItem()).apply {
            shape("DBD", "BSB", "DBD")
            setIngredient('D', Material.DIAMOND_BLOCK)
            setIngredient('B', Material.DRAGON_BREATH)
            setIngredient('S', Material.SCULK_CATALYST)
            Bukkit.addRecipe(this)
        }
    }

    fun registerEvents() {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun isTraderItem(item: ItemStack?) = item?.let {
        it.type == Material.CONDUIT &&
                it.itemMeta?.persistentDataContainer?.has(traderKey, PersistentDataType.BYTE) == true
    } ?: false

    private fun isGem(item: ItemStack?) = item?.let {
        val meta = it.itemMeta ?: return@let false
        val container = meta.persistentDataContainer

        gemKeys.any { key ->
            container.has(key, PersistentDataType.BYTE) ||
                    container.has(key, PersistentDataType.STRING) ||
                    container.has(key, PersistentDataType.INTEGER) ||
                    (meta.displayName?.contains("Gem") == true) ||
                    (key.key == "wealth_gem" && meta.displayName?.contains("Wealth") == true)
        }
    } ?: false

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        event.player.discoverRecipe(recipeKey)
    }

    @EventHandler
    fun onPlayerUseTrader(event: PlayerInteractEvent) {
        val player = event.player
        if (!event.action.isRightClick) return

        val item = event.item
        if (!isTraderItem(item)) return
        event.isCancelled = true

        var gemSlot = -1
        var currentGem: ItemStack? = null

        for (i in 0 until player.inventory.size) {
            val invItem = player.inventory.getItem(i)
            if (isGem(invItem)) {
                gemSlot = i
                currentGem = invItem
                break
            }
        }

        if (gemSlot == -1 || currentGem == null) {
            player.sendMessage("${ChatColor.RED}You must have a gem in your inventory to trade!")
            return
        }

        val blissPlugin = plugin as? BlissSMPPlugin ?: return
        val newGem = getRandomGem(blissPlugin, currentGem) ?: run {
            player.sendMessage("${ChatColor.RED}No other gems available to trade for!")
            return
        }

        player.inventory.setItem(gemSlot, null)

        item?.amount = (item?.amount ?: 1) - 1

        player.inventory.addItem(newGem)
        player.sendMessage("${ChatColor.GOLD}You traded your gem for a new one!")
    }

    private fun getRandomGem(plugin: BlissSMPPlugin, currentGem: ItemStack): ItemStack? {
        val availableGems = listOf(
            { plugin.createAstraGem() },
            { plugin.getStrengthGem()?.createStrengthGem() },
            { plugin.getWealthGem()?.createWealthGem() },
            { plugin.getPuffGem()?.createPuffGem() },
            { plugin.getFireGem()?.createFireGem() },
            { plugin.getFluxGem()?.createFluxGem() },
            { plugin.getSpeedGem()?.createSpeedGem() }
        ).filter { it() != null && !isSameGemType(it(), currentGem) }

        return availableGems.randomOrNull()?.invoke()
    }

    private fun isSameGemType(gem1: ItemStack?, gem2: ItemStack?): Boolean {
        if (gem1 == null || gem2 == null) return false
        val meta1 = gem1.itemMeta ?: return false
        val meta2 = gem2.itemMeta ?: return false
        return gemKeys.any { key ->
            (meta1.persistentDataContainer.has(key, PersistentDataType.BYTE) &&
                    meta2.persistentDataContainer.has(key, PersistentDataType.BYTE)) ||
                    (meta1.persistentDataContainer.has(key, PersistentDataType.STRING) &&
                            meta2.persistentDataContainer.has(key, PersistentDataType.STRING))
        }
    }
}