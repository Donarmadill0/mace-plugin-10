package com.plutus;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public class MaceListenerV3 implements Listener {

    private final PlutusMacePluginV3 plugin;
    private final AbilityManagerV3 manager;

    public MaceListenerV3(PlutusMacePluginV3 plugin, AbilityManagerV3 manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        new BukkitRunnable() {
            @Override
            public void run() {
                manager.sendPack(event.getPlayer());
            }
        }.runTaskLater(plugin, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack mainHandItem = player.getInventory().getItemInMainHand();

        if (plugin.isPlutusMace(mainHandItem)) {
            event.setCancelled(true);
            manager.handleAbility(player, player.isSneaking());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && plugin.isPlutusMace(attacker.getInventory().getItemInMainHand())) {
            manager.handleBloodToll(attacker);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVictimDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim) {
            manager.handleDamageSharing(victim, event.getFinalDamage());
        }
    }
}
