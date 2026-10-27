package com.lungesmash;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * LungeSmash: a spear + mace combo HUD that lives next to your hotbar. Display only: it never
 * swaps, attacks or aims for you.
 *  - spear type, Lunge level, jab readiness
 *  - lunges left (also shown as a number on the spear's hotbar slot)
 *  - a "SWAP TO MACE" prompt with predicted smash damage when you fall with the spear in hand
 *  - mace smash readiness / predicted damage
 * Toggle with /lungesmash.
 *
 * About the hunger readout: the server only sends your saturation to the client when your food or
 * health changes (or saturation hits zero), so the client's saturation value goes stale between
 * those moments. To compensate, the mod counts your own lunges (a jab with a Lunge spear at full
 * charge and food above 6) and subtracts their cost until the next real update arrives.
 */
public class LungeSmash implements ClientModInitializer {

    private record Line(String text, int color) {}

    private static final double MACE_BASE_DAMAGE = 6.0;
    private static final double MIN_SMASH_FALL = 1.5;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int RED = 0xFFFF5555;
    private static final int ORANGE = 0xFFFFAA00;

    private static boolean enabled = true;

    // Lunge tracking (client-side estimate of food points spent since the last server update)
    private static boolean wasAttackDown = false;
    private static int lastFood = -1;
    private static float lastSaturation = -1.0F;
    private static double pendingPoints = 0.0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(LungeSmash::onTick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lungesmash", "hud"), LungeSmash::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("lungesmash").executes(ctx -> {
                    enabled = !enabled;
                    ctx.getSource().sendFeedback(Component.literal("LungeSmash " + (enabled ? "enabled" : "disabled")));
                    return 1;
                })));
    }

    private static void onTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) {
            wasAttackDown = false;
            lastFood = -1;
            lastSaturation = -1.0F;
            pendingPoints = 0.0;
            return;
        }

        var food = player.getFoodData();
        int foodLevel = food.getFoodLevel();
        float saturation = food.getSaturationLevel();

        // A change in food or saturation means the server just sent a fresh value:
        // it already includes every lunge so far, so start counting again from zero.
        if (foodLevel != lastFood || saturation != lastSaturation) {
            pendingPoints = 0.0;
            lastFood = foodLevel;
            lastSaturation = saturation;
        }

        boolean attackDown = mc.screen == null && mc.options.keyAttack.isDown();
        if (attackDown && !wasAttackDown) {
            ItemStack held = player.getMainHandItem();
            if (isSpear(held)) {
                int level = lungeLevel(held);
                if (level > 0 && foodLevel > 6 && player.getAttackStrengthScale(0.0F) >= 1.0F) {
                    // One lunge costs 4 exhaustion per level = 1 food/saturation point per level.
                    pendingPoints += level;
                }
            }
        }
        wasAttackDown = attackDown;
    }

    private static void render(GuiGraphics graphics, DeltaTracker tickCounter) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        ItemStack held = player.getMainHandItem();
        boolean holdingSpear = isSpear(held);

        // Find the best spear and the mace in the hotbar.
        int spearSlot = -1;
        int lunge = 0;
        ItemStack spear = ItemStack.EMPTY;
        int maceSlot = -1;
        ItemStack mace = ItemStack.EMPTY;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (isSpear(stack)) {
                int level = lungeLevel(stack);
                if (spearSlot < 0 || level > lunge) {
                    spearSlot = i;
                    lunge = level;
                    spear = stack;
                }
            } else if (maceSlot < 0 && stack.is(Items.MACE)) {
                maceSlot = i;
                mace = stack;
            }
        }
        if (spearSlot < 0 && maceSlot < 0) {
            return;
        }

        double fall = player.fallDistance;
        boolean smashReady = fall > MIN_SMASH_FALL && !player.isFallFlying();

        // Estimated food and saturation: last real values minus lunges the server hasn't reported yet.
        var food = player.getFoodData();
        double estSaturation = food.getSaturationLevel();
        double estFood = food.getFoodLevel();
        double remaining = pendingPoints;
        double fromSaturation = Math.min(estSaturation, remaining);
        estSaturation -= fromSaturation;
        remaining -= fromSaturation;
        estFood = Math.max(0.0, estFood - remaining);

        boolean canLunge = estFood > 6.0;
        int lunges = 0;
        if (lunge > 0 && canLunge) {
            double spendable = (estFood - 6.0) + estSaturation;
            lunges = Math.max(1, (int) (spendable / lunge));
        }
        int lungeColor = !canLunge ? RED : (lunges >= 6 ? GREEN : (lunges >= 3 ? YELLOW : RED));

        List<Line> lines = new ArrayList<>();

        // The combo cue: lunge/fall with the spear, then swap to the mace to smash.
        if (holdingSpear && smashReady && maceSlot >= 0) {
            lines.add(new Line(">> SWAP TO MACE [" + (maceSlot + 1) + "]: ~" + fmt(smashDamage(mace, fall)) + " dmg <<", ORANGE));
        }

        if (spearSlot >= 0) {
            lines.add(new Line(spearName(spear) + (lunge > 0 ? "  Lunge " + roman(lunge) : "  (no Lunge)")
                    + "  [" + (spearSlot + 1) + "]", WHITE));
            if (lunge > 0) {
                if (canLunge) {
                    lines.add(new Line("Lunges left: ~" + lunges + "  (food " + (int) estFood
                            + ", sat ~" + fmt(estSaturation) + ")", lungeColor));
                } else {
                    lines.add(new Line("Can't lunge: food " + (int) estFood + " (needs 7+)", RED));
                }
            }
            if (holdingSpear) {
                float charge = player.getAttackStrengthScale(0.0F);
                lines.add(charge >= 1.0F
                        ? new Line("Jab READY", GREEN)
                        : new Line("Jab " + (int) (charge * 100) + "%", YELLOW));
            }
        }

        if (maceSlot >= 0) {
            if (smashReady) {
                lines.add(new Line("Mace [" + (maceSlot + 1) + "]: smash ~" + fmt(smashDamage(mace, fall)) + " dmg", GREEN));
            } else {
                lines.add(new Line("Mace [" + (maceSlot + 1) + "]: fall 1.5+ blocks to smash", GREY));
            }
        }

        Font font = mc.font;
        int cx = graphics.guiWidth() / 2;
        int guiHeight = graphics.guiHeight();

        // Panel to the left of the hotbar (clear of the offhand slot), right-aligned.
        int width = 0;
        for (Line line : lines) {
            width = Math.max(width, font.width(line.text()));
        }
        int lineHeight = 10;
        int rightEdge = cx - 91 - 36;
        int bottom = guiHeight - 3;
        int y = bottom - lines.size() * lineHeight;

        graphics.fill(rightEdge - width - 4, y - 3, rightEdge + 4, bottom + 1, 0x80000000);
        int lineY = y;
        for (Line line : lines) {
            graphics.drawString(font, line.text(), rightEdge - font.width(line.text()), lineY, line.color());
            lineY += lineHeight;
        }

        // Lunges-left number on the spear's own hotbar slot (like a stack count).
        if (spearSlot >= 0 && lunge > 0) {
            String badge = String.valueOf(lunges);
            int itemX = cx - 88 + spearSlot * 20;
            int itemY = guiHeight - 19;
            graphics.drawString(font, badge, itemX + 17 - font.width(badge), itemY + 9, lungeColor);
        }
    }

    private static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_spear");
    }

    /** "diamond_spear" -> "Diamond Spear" */
    private static String spearName(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        StringBuilder name = new StringBuilder();
        for (String part : path.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return name.toString();
    }

    private static int lungeLevel(ItemStack stack) {
        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants == null) {
            return 0;
        }
        for (var entry : enchants.entrySet()) {
            if (entry.getKey().is(Enchantments.LUNGE)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    private static int densityLevel(ItemStack stack) {
        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants == null) {
            return 0;
        }
        for (var entry : enchants.entrySet()) {
            if (entry.getKey().is(Enchantments.DENSITY)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    /** Raw smash damage: base + fall bonus (+4/block to 3, +2/block to 8, then +1/block) + Density. */
    private static double smashDamage(ItemStack mace, double fall) {
        double bonus;
        if (fall <= 3.0) {
            bonus = 4.0 * fall;
        } else if (fall <= 8.0) {
            bonus = 12.0 + 2.0 * (fall - 3.0);
        } else {
            bonus = 22.0 + (fall - 8.0);
        }
        return MACE_BASE_DAMAGE + bonus + 0.5 * densityLevel(mace) * fall;
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> String.valueOf(level);
        };
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
