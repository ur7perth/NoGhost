package com.ghosthit.plugin;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GhostHit extends JavaPlugin implements Listener, TabExecutor {

    private boolean enabled;
    private double reach;
    private double rayTolerance;
    private int checkDelay;
    private double javaMinCooldown;

    private boolean bedrockFixCrit;
    private double bedrockMinFall;
    private double bedrockMultiplier;
    private double bedrockMinCooldown;
    private String bedrockPrefix;

    private final Map<UUID, Integer> lastAttempt = new HashMap<>();
    /** أعلى نقطة Y وصلها اللاعب وهو بالهواء (تتبع خاص فينا) */
    private final Map<UUID, Double> peakY = new HashMap<>();
    private boolean applying = false;

    private Object floodgateApi;
    private Method floodgateCheck;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("ghosthit") != null) {
            getCommand("ghosthit").setExecutor(this);
            getCommand("ghosthit").setTabCompleter(this);
        }
        Bukkit.getScheduler().runTask(this, this::setupFloodgate);
        getLogger().info("GhostHit enabled. State: " + (enabled ? "ON" : "OFF"));
    }

    private void loadSettings() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        reach = getConfig().getDouble("reach", 3.0);
        rayTolerance = getConfig().getDouble("ray-tolerance", 0.1);
        checkDelay = Math.max(1, getConfig().getInt("check-delay-ticks", 2));
        javaMinCooldown = getConfig().getDouble("min-attack-cooldown", 0.0);
        bedrockFixCrit = getConfig().getBoolean("bedrock.fix-fake-critical", true);
        bedrockMinFall = getConfig().getDouble("bedrock.min-fall-distance", 0.1);
        bedrockMultiplier = getConfig().getDouble("bedrock.damage-multiplier", 1.0);
        bedrockMinCooldown = getConfig().getDouble("bedrock.min-attack-cooldown", 0.9);
        bedrockPrefix = getConfig().getString("bedrock.name-prefix", ".");
    }

    private void setupFloodgate() {
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgateApi = c.getMethod("getInstance").invoke(null);
            floodgateCheck = c.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable ignored) {
            floodgateApi = null;
            floodgateCheck = null;
        }
    }

    private boolean isBedrock(Player p) {
        UUID id = p.getUniqueId();
        if (id.getMostSignificantBits() == 0L) return true;
        if (floodgateApi != null && floodgateCheck != null) {
            try {
                if ((boolean) floodgateCheck.invoke(floodgateApi, id)) return true;
            } catch (Throwable ignored) { }
        }
        return bedrockPrefix != null && !bedrockPrefix.isEmpty() && p.getName().startsWith(bedrockPrefix);
    }

    // ------------------------------------------------------------------
    // تتبع السقوط الحقيقي (بدون الاعتماد على onGround / fallDistance)
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!enabled || e.getTo() == null) return;
        Player p = e.getPlayer();
        if (!isBedrock(p)) return;
        double y = e.getTo().getY();
        if (hasGroundBelow(e.getTo()) || p.isInWater() || p.isClimbing() || p.isGliding()) {
            peakY.put(p.getUniqueId(), y);
        } else {
            peakY.merge(p.getUniqueId(), y, Math::max);
        }
    }

    private boolean hasGroundBelow(Location loc) {
        World w = loc.getWorld();
        double[] off = {-0.3, 0.0, 0.3};
        for (double dx : off) {
            for (double dz : off) {
                Location l = loc.clone().add(dx, -0.1, dz);
                if (w.getBlockAt(l).getType().isSolid()) return true;
            }
        }
        return false;
    }

    /** هل اللاعب فعلاً ساقط الحين (كرتكل حقيقي)؟ */
    private boolean isReallyFalling(Player p) {
        if (hasGroundBelow(p.getLocation())) return false;
        if (p.getVelocity().getY() >= 0) return false;
        Double peak = peakY.get(p.getUniqueId());
        if (peak == null) return false;
        return (peak - p.getLocation().getY()) >= bedrockMinFall;
    }

    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttackMonitor(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;
        lastAttempt.put(p.getUniqueId(), Bukkit.getCurrentTick());
    }

    // ------------------------------------------------------------------
    // البيدروك: نلغي الكرتكل فقط إذا السيرفر حسبه كرتكل واللاعب مو ساقط فعلاً
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBedrockDamage(EntityDamageByEntityEvent e) {
        if (!enabled || applying) return;
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player p) || !isBedrock(p)) return;
        if (!(e.getEntity() instanceof Player)) return;

        double dmg = e.getDamage();
        if (bedrockFixCrit && serverWouldCrit(p) && !isReallyFalling(p)) {
            dmg = dmg / 1.5; // كرتكل وهمي (قلتش) => دمج عادي
        }
        dmg *= bedrockMultiplier;
        e.setDamage(dmg);
    }

    /** نفس شروط الكرتكل بالسيرفر الأصلي */
    @SuppressWarnings("deprecation")
    private boolean serverWouldCrit(Player p) {
        return p.getAttackCooldown() > 0.9f
                && p.getFallDistance() > 0.0f
                && !p.isOnGround()
                && !p.isClimbing()
                && !p.isInWater()
                && !p.hasPotionEffect(PotionEffectType.BLINDNESS)
                && !p.isInsideVehicle()
                && !p.isSprinting();
    }

    // ------------------------------------------------------------------
    // كشف الـ GhostHit
    // ------------------------------------------------------------------
    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (!enabled) return;
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        final Player attacker = e.getPlayer();
        if (!canAttack(attacker)) return;

        final Player target = findTarget(attacker);
        if (target == null) return;

        final int swingTick = Bukkit.getCurrentTick();
        final float cooldown = attacker.getAttackCooldown();
        final boolean bedrock = isBedrock(attacker);
        final double minCd = bedrock ? bedrockMinCooldown : javaMinCooldown;
        if (cooldown < minCd) return;

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!enabled) return;
            if (!attacker.isOnline() || !target.isOnline()) return;

            Integer last = lastAttempt.get(attacker.getUniqueId());
            if (last != null && last >= swingTick - 1) return;

            if (!canAttack(attacker) || !validVictim(target)) return;
            if (attacker.getWorld() != target.getWorld()) return;
            double max = reach + 1.0;
            if (attacker.getLocation().distanceSquared(target.getLocation()) > max * max) return;

            applyHit(attacker, target, cooldown, bedrock);
        }, checkDelay);
    }

    private boolean canAttack(Player p) {
        if (!p.isOnline() || p.isDead()) return false;
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
        return p.getWorld().getPVP();
    }

    private boolean validVictim(Player t) {
        if (t.isDead() || t.isInvulnerable()) return false;
        GameMode gm = t.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
        return t.getNoDamageTicks() <= t.getMaximumNoDamageTicks() / 2.0;
    }

    private Player findTarget(Player p) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTrace(
                eye, eye.getDirection(), reach,
                FluidCollisionMode.NEVER, true, rayTolerance,
                en -> en instanceof Player pl
                        && !pl.equals(p)
                        && !pl.isDead()
                        && pl.getGameMode() != GameMode.SPECTATOR
                        && p.canSee(pl));
        if (r == null || !(r.getHitEntity() instanceof Player t)) return null;
        return t;
    }

    private void applyHit(Player attacker, Player target, float cooldown, boolean bedrock) {
        double dmg = computeDamage(attacker, cooldown);
        if (bedrock) dmg *= bedrockMultiplier;
        if (dmg <= 0) return;

        applying = true;
        try {
            target.damage(dmg, attacker);
        } finally {
            applying = false;
        }
        attacker.resetCooldown();
    }

    private double computeDamage(Player attacker, float cd) {
        AttributeInstance attr = attacker.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        double base = attr != null ? attr.getValue() : 1.0;

        ItemStack item = attacker.getInventory().getItemInMainHand();
        double ench = 0;
        int sharp = item.getEnchantmentLevel(Enchantment.DAMAGE_ALL);
        if (sharp > 0) ench = 0.5 * sharp + 0.5;

        double scaled = base * (0.2 + cd * cd * 0.8);
        return scaled + ench * cd;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastAttempt.remove(id);
        peakY.remove(id);
    }

    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("§eGhostHit: " + (enabled ? "§aON" : "§cOFF") + " §7| /GhostHit On | Off");
            return true;
        }
        if (a[0].equalsIgnoreCase("on")) {
            enabled = true;
            getConfig().set("enabled", true);
            saveConfig();
            s.sendMessage("§aتم تشغيل GhostHit");
        } else if (a[0].equalsIgnoreCase("off")) {
            enabled = false;
            getConfig().set("enabled", false);
            saveConfig();
            s.sendMessage("§cتم إطفاء GhostHit");
        } else {
            s.sendMessage("§e/GhostHit <On|Off>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : new String[]{"On", "Off"}) {
                if (o.toLowerCase().startsWith(a[0].toLowerCase())) out.add(o);
            }
        }
        return out;
    }
}
