package io.papermc.blisssmp

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class BlissGiveCommand(private val plugin: BlissSMPPlugin) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {

        if (!sender.isOp()) {
            sender.sendMessage("${ChatColor.RED}You don't have permission to use this command!")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage("${ChatColor.RED}Usage: /bliss <give|help>")
            return true
        }

        when (args[0].lowercase()) {
            "give" -> {
                if (args.size < 3) {
                    sender.sendMessage("${ChatColor.RED}Usage: /bliss give <player> <item>")
                    return true
                }

                val playerName = args[1]
                val target = Bukkit.getPlayer(playerName)
                if (target == null) {
                    sender.sendMessage("${ChatColor.RED}Player $playerName not found!")
                    return true
                }

                when (args[2].lowercase()) {
                    "astra_gem" -> {
                        val astraGem = plugin.createAstraGem()
                        target.inventory.addItem(astraGem)
                        sender.sendMessage("${ChatColor.GREEN}Gave Astra Gem to ${target.name}")
                    }
                    "strength_gem" -> {
                        val strengthGem = plugin.getStrengthGem().createStrengthGem()
                        target.inventory.addItem(strengthGem)
                        sender.sendMessage("${ChatColor.GREEN}Gave Strength Gem to ${target.name}")
                    }
                    "wealth_gem" -> {
                        val wealthGem = plugin.getWealthGem().createWealthGem()
                        target.inventory.addItem(wealthGem)
                        sender.sendMessage("${ChatColor.GREEN}Gave Wealth Gem to ${target.name}")
                    }
                    "puff_gem" -> {
                        val puffGem = plugin.getPuffGem().createPuffGem()
                        target.inventory.addItem(puffGem)
                        sender.sendMessage("${ChatColor.GREEN}Gave Puff Gem to ${target.name}")
                    }
                    "fire_gem" -> {
                        val fireGem = plugin.getFireGem().createFireGem()
                        target.inventory.addItem(fireGem)
                        sender.sendMessage("${ChatColor.GREEN}Gave Fire Gem to ${target.name}")
                    }
                    "flux_gem" -> {
                        val fluxGem = plugin.getFluxGem().createFluxGem()
                        target.inventory.addItem(fluxGem)
                        sender.sendMessage("${ChatColor.GREEN}Gave Flux Gem to ${target.name}")
                    }
                    "speed_gem" -> {
                        val speedGem = plugin.getSpeedGem().createSpeedGem()
                        target.inventory.addItem(speedGem)
                        sender.sendMessage("${ChatColor.GREEN}Gave Speed Gem to ${target.name}")
                    }
                    else -> {
                        sender.sendMessage("${ChatColor.RED}Unknown item: ${args[2]}")
                        sender.sendMessage("${ChatColor.YELLOW}Available items: astra_gem, strength_gem, wealth_gem, puff_gem, fire_gem, flux_gem, speed_gem")
                    }
                }
            }
            "help" -> {
                sender.sendMessage("${ChatColor.GOLD}==== BlissSMP Commands ====")
                sender.sendMessage("${ChatColor.YELLOW}/bliss give <player> astra_gem ${ChatColor.WHITE}- Give an Astra Gem")
                sender.sendMessage("${ChatColor.YELLOW}/bliss give <player> strength_gem ${ChatColor.WHITE}- Give a Strength Gem")
                sender.sendMessage("${ChatColor.YELLOW}/bliss give <player> wealth_gem ${ChatColor.WHITE}- Give a Wealth Gem")
                sender.sendMessage("${ChatColor.YELLOW}/bliss give <player> puff_gem ${ChatColor.WHITE}- Give a Puff Gem")
                sender.sendMessage("${ChatColor.YELLOW}/bliss give <player> fire_gem ${ChatColor.WHITE}- Give a Fire Gem")
                sender.sendMessage("${ChatColor.YELLOW}/bliss give <player> flux_gem ${ChatColor.WHITE}- Give a Flux Gem")
                sender.sendMessage("${ChatColor.YELLOW}/bliss give <player> speed_gem ${ChatColor.WHITE}- Give a Speed Gem")
                sender.sendMessage("${ChatColor.YELLOW}/bliss help ${ChatColor.WHITE}- Show this help message")
            }
            else -> {
                sender.sendMessage("${ChatColor.RED}Unknown subcommand: ${args[0]}")
                sender.sendMessage("${ChatColor.RED}Use /bliss help for a list of commands")
            }
        }

        return true
    }
}