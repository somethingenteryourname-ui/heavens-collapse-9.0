package com.heavenscollapse.util;

import com.heavenscollapse.HeavensCollapsePlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays the "divine lightning strike" cinematic: sounds, particles and an
 * optional lightning bolt. Everything here is purely presentational - it
 * never touches health or damage.
 *
 * <p>Every world interaction takes explicit {@link Location}/{@link World}
 * parameters captured by the caller <em>before</em> the killing blow is
 * applied, so this class never depends on the target entity still being
 * alive or even still existing.</p>
 */
public class EffectsUtil {

    /** Directions the outward warden-particle burst travels in (a full sphere). */
    private static final int WARDEN_BURST_POINTS = 48;

    /** How many expanding steps the burst takes before it finishes. */
    private static final int WARDEN_BURST_STEPS = 14;

    /** Final radius, in blocks, the burst reaches. */
    private static final double WARDEN_BURST_MAX_RADIUS = 8.0;

    /** Delays (in ticks) of the echoing Sonic Boom pulses after the first. */
    private static final long[] ECHO_BOOM_DELAYS_TICKS = {5L, 10L, 16L};

    private final HeavensCollapsePlugin plugin;

    public EffectsUtil(HeavensCollapsePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Plays the full special-attack cinematic: an impact flourish centered
     * on the target, plus a much larger shockwave that erupts outward from
     * the WIELDER - this is the player's own divine power surging out,
     * not just something that happens to the victim.
     */
    public void playSpecialAttack(Player attacker, LivingEntity target) {
        World world = target.getWorld();
        Location targetLoc = target.getLocation().add(0, 1.0, 0);
        Location attackerLoc = attacker.getLocation().add(0, 1.0, 0);

        playSounds(world, targetLoc, attackerLoc);
        strikeLightning(world, targetLoc);
        playParticles(world, targetLoc, attackerLoc);
    }

    private void playSounds(World world, Location targetLoc, Location attackerLoc) {
        if (!plugin.isSoundsEnabled()) {
            return;
        }
        world.playSound(targetLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 2.5f, 0.9f);
        world.playSound(targetLoc, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.HOSTILE, 2.0f, 1.0f);
        world.playSound(targetLoc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 1.6f, 0.8f);
        world.playSound(targetLoc, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.7f);
        // The Trident's Channeling-strike thunder - distinct from the plain
        // lightning-bolt thunder above - layers in a second, slightly
        // different rumble for a fuller storm sound.
        world.playSound(targetLoc, Sound.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 1.8f, 1.0f);
        // The Warden's sonic boom is a rare, instantly recognizable vanilla
        // sound. Played from the WIELDER's location as the "source" of the
        // shockwave, at higher volume since the burst is now much bigger.
        world.playSound(attackerLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 3.0f, 1.0f);
    }

    private void strikeLightning(World world, Location targetLoc) {
        if (!plugin.isLightningEnabled()) {
            return;
        }
        if (plugin.isLightningDamage()) {
            world.strikeLightning(targetLoc);
            if (!plugin.isLightningFire()) {
                extinguishNearbyFire(targetLoc);
            }
        } else {
            // Visual-only strike: no block damage, no fire, no extra
            // entity damage - just the bolt, flash and thunder.
            world.strikeLightningEffect(targetLoc);
        }
    }

    private void playParticles(World world, Location targetLoc, Location attackerLoc) {
        if (!plugin.isParticlesEnabled()) {
            return;
        }

        // Impact flourish right on the target - the moment judgment lands.
        world.spawnParticle(Particle.FLASH, targetLoc, 2, 0, 0, 0, 0);
        world.spawnParticle(Particle.END_ROD, targetLoc, 45, 0.4, 0.9, 0.4, 0.05);
        world.spawnParticle(Particle.ELECTRIC_SPARK, targetLoc, 35, 0.5, 1.0, 0.5, 0.15);
        world.spawnParticle(Particle.CLOUD, targetLoc, 18, 0.3, 0.3, 0.3, 0.02);
        world.spawnParticle(Particle.SCULK_CHARGE_POP, targetLoc, 25, 0.5, 0.5, 0.5, 0.05);
        world.spawnParticle(Particle.SONIC_BOOM, targetLoc, 1, 0, 0, 0, 0);

        spawnBeam(world, attackerLoc, targetLoc);

        // The big shockwave now erupts from the WIELDER outward in every
        // direction (a full sphere, not just a flat ring) - much larger
        // and longer-lived than the impact flourish above.
        spawnWardenBurst(world, attackerLoc);
        scheduleEchoBooms(world, attackerLoc);
    }

    /**
     * Spawns a short trail of spark particles from the attacker toward the
     * target so the strike reads as connected to the player rather than
     * appearing out of nowhere.
     */
    private void spawnBeam(World world, Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector());
        double length = direction.length();
        if (length < 0.5) {
            return;
        }
        direction.normalize();

        int points = (int) Math.min(20, Math.max(4, length * 2));
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            Location point = from.clone().add(direction.clone().multiply(length * t));
            world.spawnParticle(Particle.ELECTRIC_SPARK, point, 2, 0.05, 0.05, 0.05, 0.0);
        }
    }

    /**
     * Animates a genuinely spherical shockwave of Warden-themed particles
     * (Sculk Soul wisps alternating with Sculk Charge Pop motes) shooting
     * outward from the wielder in every direction at once, growing larger
     * over {@value #WARDEN_BURST_STEPS} ticks. Directions are distributed
     * with a Fibonacci sphere so the burst looks like an even, expanding
     * globe rather than a flat ring. Runs as a repeating task so the
     * particles visibly travel outward instead of appearing already
     * spread out.
     */
    private void spawnWardenBurst(World world, Location center) {
        List<Vector> directions = fibonacciSphereDirections(WARDEN_BURST_POINTS);

        new BukkitRunnable() {
            int step = 1;

            @Override
            public void run() {
                if (step > WARDEN_BURST_STEPS) {
                    cancel();
                    return;
                }

                double radius = (WARDEN_BURST_MAX_RADIUS / WARDEN_BURST_STEPS) * step;
                // Alternate shells between the two particle types so the
                // burst reads as more than a single repeating layer.
                Particle shellParticle = (step % 2 == 0) ? Particle.SCULK_SOUL : Particle.SCULK_CHARGE_POP;

                for (Vector direction : directions) {
                    Location point = center.clone().add(direction.clone().multiply(radius));
                    world.spawnParticle(shellParticle, point, 1, 0, 0, 0, 0);
                }

                step++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Evenly distributes {@code count} unit-length direction vectors over
     * a sphere using the Fibonacci sphere method, so a burst built from
     * them expands as a uniform globe instead of clustering at the poles.
     */
    private List<Vector> fibonacciSphereDirections(int count) {
        List<Vector> directions = new ArrayList<>(count);
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));

        for (int i = 0; i < count; i++) {
            double y = 1.0 - (i / (double) (count - 1)) * 2.0;
            double radiusAtY = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double theta = goldenAngle * i;
            double x = Math.cos(theta) * radiusAtY;
            double z = Math.sin(theta) * radiusAtY;
            directions.add(new Vector(x, y, z));
        }

        return directions;
    }

    /**
     * A handful of echoing Sonic Boom pulses, spaced out after the first,
     * from the wielder's location - a "rolling thunder" of booms rather
     * than one flat pulse, extending how long the whole effect reads.
     */
    private void scheduleEchoBooms(World world, Location center) {
        for (long delay : ECHO_BOOM_DELAYS_TICKS) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    world.spawnParticle(Particle.SONIC_BOOM, center, 1, 0, 0, 0, 0);
                }
            }.runTaskLater(plugin, delay);
        }
    }

    private void extinguishNearbyFire(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int radius = 2;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = center.clone().add(x, y, z).getBlock();
                    Material type = block.getType();
                    if (type == Material.FIRE || type == Material.SOUL_FIRE) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }
}
