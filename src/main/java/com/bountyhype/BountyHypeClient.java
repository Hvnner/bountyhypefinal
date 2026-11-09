package com.bountyhype;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayDeque;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BountyHype - client-side only (Fabric, Mojang mappings, MC 1.21.10).
 * Watches chat for "X added $N to your bounty" style messages and plays tiered hype alerts.
 */
public class BountyHypeClient implements ClientModInitializer {

    // ====== TWEAK THESE ======
    static final double TIER1 = 1_000_000d;      // quiet chime + small banner
    static final double TIER2 = 10_000_000d;     // louder sound + big gold banner
    static final double TIER3 = 100_000_000d;    // galaxy
    static final double TIER_UFO = 500_000_000d; // ufo flies in, shakes, explodes
    static final double TIER4 = 1_000_000_000d;  // nuke

    // Matches e.g. "Steve add $ 1M to your bounty." (also "added", no $, etc.)
    // Also: "Steve added 1m to your bounty", "Steve added 2.5b to your bounty", "Steve added $1,000,000 to your bounty"
    // Group 1 = name, 2 = number, 3 = optional k/m/b/t suffix.
    static final Pattern BOUNTY_PATTERN = Pattern.compile(
        "(\\w{3,16})\\s+add(?:s|ed)?\\s+\\$?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kmbt]?)\\s+to\\s+your\\s+bounty",
        Pattern.CASE_INSENSITIVE);
    // ==========================

    static boolean debug = false;
    static final ArrayDeque<Alert> queue = new ArrayDeque<>();
    static Alert current = null;

    record Cue(long ms, SoundEvent sound, float pitch, float vol) {}

    static class Alert {
        final String name; final double amount; final int tier;
        long start = -1; int nextCue = 0; final Cue[] cues; final long duration;
        Alert(String name, double amount) {
            this.name = name; this.amount = amount;
            this.tier = amount >= TIER4 ? 5 : amount >= TIER_UFO ? 4 : amount >= TIER3 ? 3 : amount >= TIER2 ? 2 : 1;
            switch (tier) {
                case 1 -> { duration = 3500; cues = new Cue[]{
                    new Cue(0, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3f, 0.35f),
                    new Cue(120, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.8f, 0.35f)}; }
                case 2 -> { duration = 5000; cues = new Cue[]{
                    new Cue(0, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f),
                    new Cue(200, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.9f),
                    new Cue(500, SoundEvents.FIREWORK_ROCKET_BLAST, 1.0f, 0.8f)}; }
                case 3 -> { duration = 8000; cues = new Cue[]{
                    new Cue(0, SoundEvents.BEACON_ACTIVATE, 0.8f, 1.0f),
                    new Cue(300, SoundEvents.ENDER_DRAGON_GROWL, 1.6f, 0.6f),
                    new Cue(600, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0f, 1.0f),
                    new Cue(1200, SoundEvents.PLAYER_LEVELUP, 0.7f, 1.0f),
                    new Cue(2000, SoundEvents.FIREWORK_ROCKET_BLAST, 1.2f, 1.0f),
                    new Cue(3000, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f)}; }
                case 4 -> { duration = 10000; cues = new Cue[]{
                    new Cue(0, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.6f, 1.0f),
                    new Cue(0, SoundEvents.ELDER_GUARDIAN_CURSE, 1.4f, 0.8f),
                    new Cue(300, SoundEvents.BEACON_AMBIENT, 1.2f, 1.0f),
                    new Cue(1500, SoundEvents.BEACON_AMBIENT, 0.8f, 1.0f),
                    new Cue(2000, SoundEvents.TNT_PRIMED, 1.0f, 0.8f),
                    new Cue(2500, SoundEvents.TNT_PRIMED, 1.2f, 0.9f),
                    new Cue(3000, SoundEvents.TNT_PRIMED, 1.4f, 1.0f),
                    new Cue(3450, SoundEvents.TNT_PRIMED, 1.6f, 1.0f),
                    new Cue(3850, SoundEvents.TNT_PRIMED, 1.8f, 1.0f),
                    new Cue(4300, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.8f, 3.0f),
                    new Cue(4300, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.7f, 1.0f),
                    new Cue(4400, SoundEvents.WITHER_SPAWN, 1.0f, 0.8f),
                    new Cue(4600, SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f, 1.0f),
                    new Cue(5000, SoundEvents.ENDER_DRAGON_GROWL, 1.2f, 0.8f),
                    new Cue(5800, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)}; }
                default -> { duration = 11000; cues = new Cue[]{
                    new Cue(0, SoundEvents.TNT_PRIMED, 0.5f, 1.0f),
                    new Cue(450, SoundEvents.TNT_PRIMED, 0.6f, 1.0f),
                    new Cue(900, SoundEvents.TNT_PRIMED, 0.7f, 1.0f),
                    new Cue(1300, SoundEvents.TNT_PRIMED, 0.9f, 1.0f),
                    new Cue(1700, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f, 3.0f),
                    new Cue(1700, SoundEvents.WITHER_SPAWN, 0.6f, 1.0f),
                    new Cue(1750, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.5f, 1.0f),
                    new Cue(2500, SoundEvents.ENDER_DRAGON_GROWL, 0.6f, 1.0f),
                    new Cue(4000, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f)}; }
            }
        }
    }

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> handleChat(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> handleChat(message.getString()));

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
            ResourceLocation.fromNamespaceAndPath("bountyhype", "hype"),
            (g, tick) -> render(g));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, reg) ->
            dispatcher.register(ClientCommandManager.literal("bountyhype")
                .then(ClientCommandManager.literal("debug").executes(ctx -> {
                    debug = !debug;
                    ctx.getSource().sendFeedback(Component.literal("[BountyHype] debug " + (debug ? "ON" : "OFF")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("test")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .then(ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> {
                                queue.add(new Alert(StringArgumentType.getString(ctx, "name"),
                                    DoubleArgumentType.getDouble(ctx, "amount")));
                                return 1;
                            }))))));
    }

    static String lastMsg = "";
    static long lastMsgTime = 0;

    static void handleChat(String raw) {
        String msg = raw.replaceAll("§.", "").trim();
        // ignore the exact same line arriving twice within 2s (chat + action bar)
        long nowMs = System.currentTimeMillis();
        if (msg.equals(lastMsg) && nowMs - lastMsgTime < 2000) return;
        lastMsg = msg; lastMsgTime = nowMs;
        Matcher m = BOUNTY_PATTERN.matcher(msg);
        if (!m.find()) {
            if (debug && msg.toLowerCase().contains("bounty")) {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.getChat()
                    .addMessage(Component.literal("[BountyHype] no match: " + msg)));
            }
            return;
        }
        double amount = parse(m.group(2), m.group(3));
        if (amount >= TIER1) queue.add(new Alert(m.group(1), amount));
    }

    static double parse(String num, String suffix) {
        double v = Double.parseDouble(num.replace(",", ""));
        return switch (suffix.toLowerCase()) {
            case "k" -> v * 1_000d;
            case "m" -> v * 1_000_000d;
            case "b" -> v * 1_000_000_000d;
            case "t" -> v * 1_000_000_000_000d;
            default -> v;
        };
    }

    // ================= RENDERING =================

    static int argb(int a, int r, int g, int b) {
        return (Math.max(0, Math.min(255, a)) << 24) | (r << 16) | (g << 8) | b;
    }

    static void circle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int hw = (int) Math.sqrt((double) r * r - (double) dy * dy);
            g.fill(cx - hw, cy + dy, cx + hw, cy + dy + 1, color);
        }
    }

    static void bigText(GuiGraphics g, String text, float x, float y, float scale, int color) {
        Minecraft mc = Minecraft.getInstance();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.drawCenteredString(mc.font, Component.literal(text), 0, 0, color);
        g.pose().popMatrix();
    }

    static String money(double v) { return String.format("$%,.0f", v); }

    static void render(GuiGraphics g) {
        long now = System.currentTimeMillis();
        if (current == null) {
            current = queue.poll();
            if (current == null) return;
            current.start = now;
        }
        Alert a = current;
        long t = now - a.start;
        if (t > a.duration) { current = null; return; }

        Minecraft mc = Minecraft.getInstance();
        while (a.nextCue < a.cues.length && t >= a.cues[a.nextCue].ms()) {
            Cue c = a.cues[a.nextCue++];
            mc.getSoundManager().play(SimpleSoundInstance.forUI(c.sound(), c.pitch(), c.vol()));
        }

        int w = g.guiWidth(), h = g.guiHeight();
        float fadeOut = (float) Math.min(1.0, (a.duration - t) / 700.0);
        switch (a.tier) {
            case 1 -> small(g, a, t, w, fadeOut);
            case 2 -> medium(g, a, t, w, fadeOut);
            case 3 -> galaxy(g, a, t, w, h, fadeOut);
            case 4 -> ufo(g, a, t, w, h, fadeOut);
            default -> nuke(g, a, t, w, h, fadeOut);
        }
    }

    static void small(GuiGraphics g, Alert a, long t, int w, float fade) {
        float slide = (float) Math.min(1.0, t / 250.0);
        int y = (int) (-40 + slide * 60);
        int al = (int) (200 * fade);
        g.fill(w / 2 - 110, y, w / 2 + 110, y + 32, argb(al, 15, 15, 25));
        g.fill(w / 2 - 110, y, w / 2 - 108, y + 32, argb((int) (255 * fade), 80, 220, 120));
        bigText(g, a.name, w / 2f, y + 6, 1.0f, argb((int) (255 * fade), 255, 255, 255));
        bigText(g, "+" + money(a.amount), w / 2f, y + 18, 1.0f, argb((int) (255 * fade), 90, 255, 130));
    }

    static void medium(GuiGraphics g, Alert a, long t, int w, float fade) {
        float slide = (float) Math.min(1.0, t / 250.0);
        int y = (int) (-70 + slide * 100);
        float pulse = 1f + 0.06f * (float) Math.sin(t / 90.0);
        int al = (int) (220 * fade);
        g.fill(w / 2 - 150, y, w / 2 + 150, y + 56, argb(al, 25, 18, 5));
        int gold = argb((int) (255 * fade), 255, 200, 40);
        g.fill(w / 2 - 150, y, w / 2 + 150, y + 2, gold);
        g.fill(w / 2 - 150, y + 54, w / 2 + 150, y + 56, gold);
        bigText(g, a.name, w / 2f, y + 8, 1.6f, argb((int) (255 * fade), 255, 255, 255));
        bigText(g, "+" + money(a.amount), w / 2f, y + 30, 1.9f * pulse, gold);
    }

    static void galaxy(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        float fadeIn = (float) Math.min(1.0, t / 500.0);
        float k = Math.min(fade, fadeIn);
        g.fill(0, 0, w, h, argb((int) (235 * k), 3, 0, 15));

        Random rnd = new Random(42);
        for (int i = 0; i < 160; i++) {
            int x = rnd.nextInt(w), y = rnd.nextInt(h);
            double tw = 0.5 + 0.5 * Math.sin(t / 250.0 + i);
            int s = rnd.nextInt(3) == 0 ? 2 : 1;
            g.fill(x, y, x + s, y + s, argb((int) (255 * k * tw), 255, 255, 255));
        }

        int cx = w / 2, cy = h / 2;
        double maxR = Math.min(w, h) * 0.55 * Math.min(1.0, t / 900.0 + 0.2);
        double spin = t * 0.0006;
        for (int arm = 0; arm < 4; arm++) {
            for (int i = 0; i < 140; i++) {
                double f = i / 140.0;
                double r = f * maxR;
                double ang = arm * Math.PI / 2 + r * 0.035 + spin;
                int x = (int) (cx + Math.cos(ang) * r * 1.6);
                int y = (int) (cy + Math.sin(ang) * r * 0.7);
                int rr = (int) (120 + 135 * f), gg = (int) (60 + 60 * (1 - f)), bb = (int) (255 - 100 * f);
                int s = 1 + (i % 7 == 0 ? 2 : (i % 3 == 0 ? 1 : 0));
                g.fill(x, y, x + s, y + s, argb((int) (230 * k * (1 - f * 0.5)), rr, gg, bb));
            }
        }
        circle(g, cx, cy, 26, argb((int) (60 * k), 200, 150, 255));
        circle(g, cx, cy, 14, argb((int) (110 * k), 255, 220, 255));
        circle(g, cx, cy, 6, argb((int) (255 * k), 255, 255, 255));

        float pulse = 1f + 0.07f * (float) Math.sin(t / 120.0);
        int al = (int) (255 * k);
        bigText(g, "GALACTIC BOUNTY!", cx, cy - 78, 2.0f, argb(al, 190, 150, 255));
        bigText(g, a.name, cx, cy - 50, 3.0f * pulse, argb(al, 255, 255, 255));
        bigText(g, "+" + money(a.amount), cx, cy + 62, 3.6f * pulse, argb(al, 255, 215, 60));
    }


    static void ellipse(GuiGraphics g, int cx, int cy, int rx, int ry, int color) {
        for (int dy = -ry; dy <= ry; dy++) {
            int hw = (int) (rx * Math.sqrt(1.0 - (double) dy * dy / ((double) ry * ry)));
            g.fill(cx - hw, cy + dy, cx + hw, cy + dy + 1, color);
        }
    }

    static void ufoShip(GuiGraphics g, int x, int y, long t, float fade) {
        int al = (int) (255 * fade);
        ellipse(g, x, y + 12, 52, 12, argb(al, 70, 75, 95));
        ellipse(g, x, y, 90, 18, argb(al, 150, 160, 185));
        ellipse(g, x, y - 6, 70, 7, argb(al, 200, 210, 230));
        ellipse(g, x, y - 18, 34, 22, argb((int) (210 * fade), 120, 230, 255));
        ellipse(g, x - 10, y - 26, 10, 7, argb((int) (230 * fade), 230, 255, 255));
        for (int i = 0; i < 7; i++) {
            int lx = x - 72 + i * 24, ly = y + 4;
            int ph = (int) ((t / 110 + i) % 3);
            int c = ph == 0 ? argb(al, 255, 60, 60) : ph == 1 ? argb(al, 255, 230, 60) : argb(al, 80, 255, 120);
            g.fill(lx - 3, ly - 2, lx + 3, ly + 3, c);
        }
    }

    static void ufo(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        Minecraft mc = Minecraft.getInstance();
        int cx = w / 2, cy = h / 2;
        long boom = 4300;

        // whole-scene shake while hovering and right after the blast
        float sx = 0, sy = 0;
        if (t >= 3300 && t < boom) {
            float amp = 4f + 10f * (t - 3300) / 1000f;
            sx = (float) ((Math.random() - 0.5) * amp); sy = (float) ((Math.random() - 0.5) * amp);
        } else if (t >= boom && t < boom + 900) {
            float amp = 16f * (1f - (t - boom) / 900f);
            sx = (float) ((Math.random() - 0.5) * amp); sy = (float) ((Math.random() - 0.5) * amp);
        }
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);
        g.fill(-20, -20, w + 20, h + 20, argb((int) (130 * Math.min(1.0, t / 600.0) * fade), 0, 5, 20));

        if (t < boom) {
            double fly = Math.min(1.0, t / 2000.0);
            double ease = 1 - Math.pow(1 - fly, 3);
            int x = (int) (-100 + (cx + 100) * ease);
            int y = (int) (30 + (cy - 30) * ease + (1 - ease) * Math.sin(t / 120.0) * 25);
            int ux = x, uy = y;
            if (t >= 2000) {
                double sh = (t - 2000) / 2300.0;
                double amp = 2 + 16 * sh;
                ux = cx + (int) ((Math.random() - 0.5) * amp);
                uy = cy + (int) ((Math.random() - 0.5) * amp);
            }
            if (t >= 1500) { // tractor beam
                int bh = h - uy;
                for (int i = 0; i < bh; i += 2) {
                    int half = 14 + i / 6;
                    g.fill(ux - half, uy + 14 + i, ux + half, uy + 16 + i, argb((int) (45 * fade), 120, 255, 150));
                }
            }
            ufoShip(g, ux, uy, t, fade);
            if (t < 2000 && (t / 150) % 2 == 0) {
                bigText(g, "UFO INCOMING", cx, 20, 2.0f, argb((int) (255 * fade), 120, 255, 150));
            }
            if (t >= 2000) {
                bigText(g, "!!! WARNING !!!", cx, 20, 2.0f, argb((int) (255 * fade), 255, 70, 70));
            }
            g.pose().popMatrix();
            return;
        }

        long e = t - boom;
        // fireball
        float decay = (float) Math.max(0.0, 1.0 - e / 1800.0);
        int fr = (int) Math.min(150, 25 + e * 0.3);
        circle(g, cx, cy, fr, argb((int) (210 * decay * fade), 255, 90, 15));
        circle(g, cx, cy, (int) (fr * 0.72), argb((int) (230 * decay * fade), 255, 170, 40));
        circle(g, cx, cy, (int) (fr * 0.4), argb((int) (255 * decay * fade), 255, 245, 190));

        // debris
        Random rnd = new Random(7);
        for (int i = 0; i < 60; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double spd = 0.12 + rnd.nextDouble() * 0.45;
            int size = 3 + rnd.nextInt(4);
            int kind = rnd.nextInt(3);
            double dx = Math.cos(ang) * spd * e;
            double dy = Math.sin(ang) * spd * e + 0.00025 * e * e;
            float da = (float) Math.max(0.0, 1.0 - e / 3500.0) * fade;
            int c = kind == 0 ? argb((int) (255 * da), 150, 160, 185)
                  : kind == 1 ? argb((int) (255 * da), 90, 255, 130) : argb((int) (255 * da), 255, 150, 40);
            g.fill((int) (cx + dx), (int) (cy + dy), (int) (cx + dx) + size, (int) (cy + dy) + size, c);
        }

        // shockwave
        double rad = e * 0.8;
        if (rad < w) {
            int ra = (int) (200 * fade * Math.max(0, 1 - rad / (w * 0.9)));
            for (int i = 0; i < 360; i += 3) {
                double ang = Math.toRadians(i);
                int x = (int) (cx + Math.cos(ang) * rad * 1.3);
                int y = (int) (cy + Math.sin(ang) * rad * 0.8);
                g.fill(x, y, x + 3, y + 3, argb(ra, 200, 255, 220));
            }
        }

        // big name
        if (e > 500) {
            float ta = (float) Math.min(1.0, (e - 500) / 500.0) * fade;
            int tal = (int) (255 * ta);
            float pulse = 1f + 0.05f * (float) Math.sin(t / 100.0);
            float nameScale = Math.min(5.5f, w * 0.9f / Math.max(1, mc.font.width(a.name)));
            bigText(g, "UFO DESTROYED BY", cx, cy - 78, 1.8f, argb(tal, 120, 255, 150));
            bigText(g, a.name, cx + 3, cy - 40 + 3, nameScale * pulse, argb(tal, 0, 40, 10));
            bigText(g, a.name, cx, cy - 40, nameScale * pulse, argb(tal, 255, 255, 255));
            bigText(g, "+" + money(a.amount), cx, cy + 40, 3.2f * pulse, argb(tal, 255, 215, 60));
        }
        g.pose().popMatrix();

        if (e < 700) {
            g.fill(0, 0, w, h, argb((int) (255 * (1 - e / 700.0)), 255, 255, 255));
        }
    }

    static void nuke(GuiGraphics g, Alert a, long t, int w, int h, float fade) {
        int cx = w / 2;
        int al = (int) (255 * fade);

        // Phase 1: warning countdown
        if (t < 1700) {
            double pulse = 0.5 + 0.5 * Math.sin(t / 60.0);
            g.fill(0, 0, w, h, argb((int) (90 * pulse), 255, 0, 0));
            bigText(g, "!!! NUKE INCOMING !!!", cx, h / 2f - 20, 3.0f, argb(255, 255, 60, 60));
            bigText(g, a.name + " is dropping " + money(a.amount), cx, h / 2f + 20, 1.5f, argb(255, 255, 255, 255));
            return;
        }

        long e = t - 1700;
        // screen shake
        float sx = 0, sy = 0;
        if (e < 1200) {
            float amp = 14f * (1f - e / 1200f);
            sx = (float) ((Math.random() - 0.5) * amp);
            sy = (float) ((Math.random() - 0.5) * amp);
        }
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);

        // backdrop
        g.fill(-20, -20, w + 20, h + 20, argb((int) (150 * fade), 40, 10, 0));

        int baseY = (int) (h * 0.80);
        double p = Math.min(1.0, e / 2600.0);
        int stemH = (int) (130 * p), capR = (int) (75 * p) + 5;
        int capY = baseY - stemH - (int) (capR * 0.5);

        // ground fire
        circle(g, cx, baseY, (int) (60 * p) + 8, argb((int) (230 * fade), 255, 120, 20));
        // stem
        for (int i = 0; i < stemH; i++) {
            int half = (int) (12 + 10 * (1 - i / Math.max(1.0, (double) stemH)));
            g.fill(cx - half, baseY - i - 1, cx + half, baseY - i, argb((int) (230 * fade), 210, 90 + i / 2, 20));
        }
        // cap
        circle(g, cx, capY, capR, argb((int) (235 * fade), 230, 90, 15));
        circle(g, cx - capR / 4, capY - capR / 5, (int) (capR * 0.7), argb((int) (235 * fade), 255, 160, 40));
        circle(g, cx - capR / 3, capY - capR / 3, (int) (capR * 0.35), argb((int) (240 * fade), 255, 235, 150));

        // shockwave rings
        for (int ring = 0; ring < 2; ring++) {
            double rad = Math.max(0, (e - ring * 250) * 0.9);
            if (rad <= 0 || rad > w) continue;
            int ra = (int) (200 * fade * Math.max(0, 1 - rad / (w * 0.9)));
            for (int i = 0; i < 360; i += 3) {
                double ang = Math.toRadians(i);
                int x = (int) (cx + Math.cos(ang) * rad * 1.5);
                int y = (int) (baseY + Math.sin(ang) * rad * 0.35);
                g.fill(x, y, x + 3, y + 2, argb(ra, 255, 220, 150));
            }
        }

        // text
        if (e > 600) {
            float ta = (float) Math.min(1.0, (e - 600) / 600.0) * fade;
            int tal = (int) (255 * ta);
            float pulse = 1f + 0.06f * (float) Math.sin(t / 100.0);
            bigText(g, "BOUNTY NUKED", cx, 28, 2.6f, argb(tal, 255, 70, 40));
            bigText(g, a.name, cx, 62, 3.2f * pulse, argb(tal, 255, 255, 255));
            bigText(g, "+" + money(a.amount), cx, 100, 4.0f * pulse, argb(tal, 255, 215, 60));
        }
        g.pose().popMatrix();

        // white flash on detonation
        if (e < 1000) {
            g.fill(0, 0, w, h, argb((int) (255 * (1 - e / 1000.0)), 255, 255, 255));
        }
    }
}
