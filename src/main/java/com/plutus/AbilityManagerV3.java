package com.plutus;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AbilityManagerV3 {

    private final PlutusMacePluginV3 plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // Cooldowns (in milliseconds timestamp when available next)
    private final Map<UUID, Long> dashCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pactCooldowns = new ConcurrentHashMap<>();

    // Active Pact target sets: Caster UUID -> Set of Target UUIDs
    private final Map<UUID, Set<UUID>> activePacts = new ConcurrentHashMap<>();
    // Reverse map: Target UUID -> Caster UUID
    private final Map<UUID, UUID> targetToOwner = new ConcurrentHashMap<>();

    // Hit counter per attacker for Blood Toll threshold mode
    private final Map<UUID, Integer> hitCounters = new ConcurrentHashMap<>();

    // Recursion guard for damage sharing
    private final Set<UUID> currentlySharing = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Resource pack state
    private byte[] packBytes;
    private String packHash;
    private String hostedPackUrl;

    private BukkitTask visualTask;
    private BukkitTask hudTask;

    public AbilityManagerV3(PlutusMacePluginV3 plugin) {
        this.plugin = plugin;
        startVisualTask();
        startHudTask();
        generateAndHostPack();
    }

    public void stopTasks() {
        if (visualTask != null) visualTask.cancel();
        if (hudTask != null) hudTask.cancel();
    }

    // ==========================================
    // RESOURCE PACK AUTO-HOSTING
    // ==========================================

    private void generateAndHostPack() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                        addFileToZip(zos, "pack.mcmeta", "{\"pack\":{\"description\":\"Plutus Mace Assets\",\"pack_format\":37}}");
                        addResourceToZip(zos, "assets/minecraft/textures/item/plutus_mace.png");
                        addResourceToZip(zos, "assets/minecraft/models/item/plutus_mace.json");
                        addResourceToZip(zos, "assets/minecraft/models/item/mace.json");
                    }
                    packBytes = baos.toByteArray();
                    MessageDigest digest = MessageDigest.getInstance("SHA-1");
                    byte[] hash = digest.digest(packBytes);
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hash) sb.append(String.format("%02x", b));
                    packHash = sb.toString();

                    hostedPackUrl = uploadToCatbox(packBytes);
                    if (hostedPackUrl != null) {
                        plugin.getLogger().info("Resource pack hosted at: " + hostedPackUrl);
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Error hosting resource pack: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void addFileToZip(ZipOutputStream zos, String path, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private void addResourceToZip(ZipOutputStream zos, String path) throws IOException {
        InputStream is = plugin.getResource(path);
        if (is == null) return;
        zos.putNextEntry(new ZipEntry(path));
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) > 0) zos.write(buffer, 0, len);
        zos.closeEntry();
        is.close();
    }

    private String uploadToCatbox(byte[] data) {
        try {
            String boundary = "*****" + System.currentTimeMillis() + "*****";
            HttpURLConnection conn = (HttpURLConnection) new URL("https://catbox.moe/user/api.php").openConnection();
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                dos.writeBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"reqtype\"\r\n\r\nfileupload\r\n");
                dos.writeBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"plutus.zip\"\r\nContent-Type: application/zip\r\n\r\n");
                dos.write(data);
                dos.writeBytes("\r\n--" + boundary + "--\r\n");
            }
            if (conn.getResponseCode() == 200) {
                try (Scanner s = new Scanner(conn.getInputStream())) { return s.hasNext() ? s.next() : null; }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void sendPack(Player player) {
        if (hostedPackUrl != null && packHash != null) {
            player.setResourcePack(hostedPackUrl, packHash, true, mm.deserialize("<red>Plutus Mace texture pack loading..."));
        }
    }

    // ==========================================
    // ABILITY DISPATCHER
    // ==========================================

    public void handleAbility(Player player, boolean isSneaking) {
        if (isSneaking) {
            triggerPact(player);
        } else {
            triggerDash(player);
        }
    }

    // ==========================================
    // ABILITY 1: BLOOD DASH
    // ==========================================

    private void triggerDash(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        int cdSec = plugin.getConfig().getInt("dash.cooldown-seconds", 10);
        long cdMs = cdSec * 1000L;

        if (dashCooldowns.getOrDefault(id, 0L) > now) {
            long remainingSec = (dashCooldowns.get(id) - now + 999) / 1000;
            player.sendActionBar(mm.deserialize("<red>🩸 | <gray>" + remainingSec + "s"));
            return;
        }

        dashCooldowns.put(id, now + cdMs);

        // Sound
        try {
            String soundName = plugin.getConfig().getString("sounds.dash", "ENTITY_WITHER_SHOOT");
            player.getWorld().playSound(player.getLocation(), Sound.valueOf(soundName), 1.0f, 1.0f);
        } catch (Exception ignored) {}

        // Burst particles at cast position
        Location castLoc = player.getLocation().add(0, 1, 0);
        castLoc.getWorld().spawnParticle(Particle.CRIMSON_SPORE, castLoc, 35, 0.4, 0.5, 0.4, 0.03);
        castLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, castLoc, 25, 0.3, 0.4, 0.3, 0.02);

        // Impulse physics
        double forwardImpulse = plugin.getConfig().getDouble("dash.forward-impulse", 1.8);
        double upwardImpulse = plugin.getConfig().getDouble("dash.upward-impulse", 0.25);
        Vector dir = player.getLocation().getDirection().normalize().multiply(forwardImpulse);
        dir.setY(upwardImpulse);
        player.setVelocity(dir);

        // Trail particles during dash (8 ticks)
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks >= 8 || !player.isOnline()) {
                    cancel();
                    // Impact particles at end
                    if (player.isOnline()) {
                        Location impLoc = player.getLocation().add(0, 1, 0);
                        impLoc.getWorld().spawnParticle(Particle.CRIMSON_SPORE, impLoc, 30, 0.5, 0.5, 0.5, 0.03);
                        impLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, impLoc, 20, 0.3, 0.4, 0.3, 0.02);
                    }
                    return;
                }
                Location pLoc = player.getLocation().add(0, 0.8, 0);
                pLoc.getWorld().spawnParticle(Particle.CRIMSON_SPORE, pLoc, 12, 0.2, 0.3, 0.2, 0.02);
                pLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, pLoc, 8, 0.2, 0.3, 0.2, 0.02);
                ticks++;
            }
        }.runTaskTimer(plugin, 1L, 1L);

        // Damage nearby players
        double range = plugin.getConfig().getDouble("dash.damage-radius", 4.0);
        double dmg = plugin.getConfig().getDouble("dash.damage-amount", 3.0);
        for (Entity entity : player.getNearbyEntities(range, range, range)) {
            if (entity instanceof Player target && !target.equals(player)) {
                target.damage(dmg, player);
                target.getWorld().spawnParticle(Particle.CRIMSON_SPORE, target.getLocation().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.02);
            }
        }
    }

    // ==========================================
    // ABILITY 2: BLOOD PACT (DAMAGE LINK)
    // ==========================================

    private void triggerPact(Player player) {
        UUID ownerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        int cdSec = plugin.getConfig().getInt("pact.cooldown-seconds", 25);
        long cdMs = cdSec * 1000L;

        if (pactCooldowns.getOrDefault(ownerId, 0L) > now) {
            long remainingSec = (pactCooldowns.get(ownerId) - now + 999) / 1000;
            player.sendActionBar(mm.deserialize("<dark_red>⛓ | <gray>" + remainingSec + "s"));
            return;
        }

        // Clear existing pact for this owner
        clearPact(ownerId);

        double range = plugin.getConfig().getDouble("pact.link-radius", 15.0);
        int maxTargets = plugin.getConfig().getInt("pact.max-targets", 3);
        Set<UUID> targets = new HashSet<>();

        for (Entity entity : player.getNearbyEntities(range, range, range)) {
            if (entity instanceof Player target && !target.equals(player)) {
                if (targets.size() < maxTargets) {
                    UUID tid = target.getUniqueId();
                    targets.add(tid);
                    targetToOwner.put(tid, ownerId);

                    // Target notify & sound
                    target.sendActionBar(mm.deserialize("<dark_red>⛓ | <red>BLOOD BOUND"));
                    try {
                        String linkSound = plugin.getConfig().getString("sounds.pact-link", "BLOCK_CHAIN_PLACE");
                        target.getWorld().playSound(target.getLocation(), Sound.valueOf(linkSound), 1.0f, 1.0f);
                    } catch (Exception ignored) {}

                    Location tLoc = target.getLocation().add(0, 1, 0);
                    tLoc.getWorld().spawnParticle(Particle.CRIMSON_SPORE, tLoc, 25, 0.3, 0.5, 0.3, 0.02);
                    tLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, tLoc, 15, 0.2, 0.3, 0.2, 0.02);
                }
            }
        }

        if (targets.isEmpty()) {
            player.sendMessage(mm.deserialize("<red>No available players nearby to link."));
            return;
        }

        pactCooldowns.put(ownerId, now + cdMs);
        activePacts.put(ownerId, targets);

        // Sound & actionbar for owner
        try {
            String castSound = plugin.getConfig().getString("sounds.pact-cast", "ENTITY_WITHER_AMBIENT");
            player.getWorld().playSound(player.getLocation(), Sound.valueOf(castSound), 1.0f, 1.0f);
        } catch (Exception ignored) {}
        player.sendActionBar(mm.deserialize("<dark_red>⛓ | <red>" + targets.size() + " LINKED"));

        // Expiry task
        int durationSec = plugin.getConfig().getInt("pact.duration-seconds", 10);
        new BukkitRunnable() {
            @Override
            public void run() {
                clearPact(ownerId);
                if (player.isOnline()) {
                    player.sendMessage(mm.deserialize("<gray>Your Blood Pact has expired."));
                }
            }
        }.runTaskLater(plugin, durationSec * 20L);
    }

    private void clearPact(UUID ownerId) {
        Set<UUID> targets = activePacts.remove(ownerId);
        if (targets != null) {
            for (UUID tid : targets) {
                targetToOwner.remove(tid);
            }
        }
    }

    // ==========================================
    // DAMAGE SHARING & BLOOD TOLL
    // ==========================================

    public void handleDamageSharing(Player victim, double damage) {
        UUID victimId = victim.getUniqueId();
        if (currentlySharing.contains(victimId)) return;

        UUID ownerId = targetToOwner.get(victimId);
        if (ownerId == null) return;

        Set<UUID> targets = activePacts.get(ownerId);
        if (targets == null || !targets.contains(victimId)) return;

        for (UUID targetId : targets) {
            if (targetId.equals(victimId)) continue;
            Player other = plugin.getServer().getPlayer(targetId);
            if (other != null && other.isOnline()) {
                currentlySharing.add(targetId);
                try {
                    other.damage(damage);
                    Location oLoc = other.getLocation().add(0, 1, 0);
                    oLoc.getWorld().spawnParticle(Particle.CRIMSON_SPORE, oLoc, 15, 0.3, 0.3, 0.3, 0.02);
                    try {
                        other.getWorld().playSound(other.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.8f, 1.2f);
                    } catch (Exception ignored) {}
                } finally {
                    currentlySharing.remove(targetId);
                }
            }
        }
    }

    public void handleBloodToll(Player attacker) {
        String mode = plugin.getConfig().getString("self-damage-mode", "EVERY_HIT");
        double dmg = plugin.getConfig().getDouble("self-damage-amount", 1.0);

        boolean applyDamage = false;

        if ("EVERY_HIT".equalsIgnoreCase(mode)) {
            applyDamage = true;
        } else {
            UUID id = attacker.getUniqueId();
            int currentHits = hitCounters.getOrDefault(id, 0) + 1;
            int threshold = plugin.getConfig().getInt("self-damage-hit-count", 3);
            if (currentHits >= threshold) {
                hitCounters.put(id, 0);
                applyDamage = true;
            } else {
                hitCounters.put(id, currentHits);
            }
        }

        if (applyDamage) {
            attacker.damage(dmg);
            attacker.sendActionBar(mm.deserialize("<dark_red>🩸 | <red>BLOOD TOLL"));
            try {
                String soundName = plugin.getConfig().getString("sounds.toll-trigger", "ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH");
                attacker.getWorld().playSound(attacker.getLocation(), Sound.valueOf(soundName), 0.8f, 0.8f);
            } catch (Exception ignored) {}
            Location aLoc = attacker.getLocation().add(0, 1, 0);
            aLoc.getWorld().spawnParticle(Particle.CRIMSON_SPORE, aLoc, 20, 0.3, 0.4, 0.3, 0.02);
            aLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, aLoc, 12, 0.2, 0.3, 0.2, 0.02);
        }
    }

    // ==========================================
    // VISUAL LINKS & HUD TASKS
    // ==========================================

    private void startVisualTask() {
        visualTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<UUID, Set<UUID>> entry : activePacts.entrySet()) {
                    List<Player> onlineTargets = new ArrayList<>();
                    for (UUID tid : entry.getValue()) {
                        Player t = plugin.getServer().getPlayer(tid);
                        if (t != null && t.isOnline()) onlineTargets.add(t);
                    }
                    for (int i = 0; i < onlineTargets.size(); i++) {
                        for (int j = i + 1; j < onlineTargets.size(); j++) {
                            drawLink(onlineTargets.get(i), onlineTargets.get(j));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 4L);
    }

    private void drawLink(Player a, Player b) {
        if (!a.getWorld().equals(b.getWorld())) return;
        Location start = a.getLocation().add(0, 1, 0);
        Location end = b.getLocation().add(0, 1, 0);
        Vector vec = end.toVector().subtract(start.toVector());
        double dist = vec.length();
        if (dist == 0) return;
        vec.normalize();

        int points = 7;
        for (int i = 0; i < points; i++) {
            double ratio = (double) i / (points - 1);
            Location pt = start.clone().add(vec.clone().multiply(dist * ratio));
            pt.getWorld().spawnParticle(Particle.CRIMSON_SPORE, pt, 1, 0, 0, 0, 0);
            pt.getWorld().spawnParticle(Particle.LARGE_SMOKE, pt, 1, 0, 0, 0, 0);
        }
    }

    private void startHudTask() {
        hudTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (plugin.isPlutusMace(player.getInventory().getItemInMainHand())) {
                        UUID id = player.getUniqueId();
                        long dashCd = Math.max(0, (dashCooldowns.getOrDefault(id, 0L) - now + 999) / 1000);
                        long pactCd = Math.max(0, (pactCooldowns.getOrDefault(id, 0L) - now + 999) / 1000);
                        Set<UUID> targets = activePacts.get(id);
                        int linkedCount = (targets != null) ? targets.size() : 0;

                        player.sendActionBar(mm.deserialize(
                            "<red>🩸 <white>" + dashCd + "s <dark_gray>| <dark_red>⛓ <white>" + linkedCount + " <dark_gray>| <red>⌛ <white>" + pactCd + "s"
                        ));
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        clearPact(id);
        UUID ownerId = targetToOwner.remove(id);
        if (ownerId != null) {
            Set<UUID> targets = activePacts.get(ownerId);
            if (targets != null) targets.remove(id);
        }
        dashCooldowns.remove(id);
        pactCooldowns.remove(id);
        hitCounters.remove(id);
    }
}
