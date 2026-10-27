package com.lungesmash;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
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
 * LungeSmash: a spear + mace combo HUD. Display only: it never swaps, attacks or aims for you.
 *  - spear jab readiness (attack bar) and Lunge level
 *  - how many lunges your food can pay for (Lunge costs 4 x level exhaustion and needs more than 6 food)
 *  - a "SWAP TO MACE" prompt with predicted smash damage when you are falling with the spear in hand
 * Toggle with /lungesmash.
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

    @Override
    public void onInitializeClient() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lungesmash", "hud"), LungeSmash::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("lungesmash").executes(ctx -> {
                    enabled = !enabled;
                    ctx.getSource().sendFeedback(Component.literal("LungeSmash " + (enabled ? "enabled" : "disabled")));
                    return 1;
                })));
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
        boolean holdingMace = held.is(Items.MACE);
        if (!holdingSpear && !holdingMace) {
            return;
        }

        // Find the best spear and the mace in the hotbar.
        int spearSlot = -1;
        int lunge = 0;
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
                }
            } else if (maceSlot < 0 && stack.is(Items.MACE)) {
                maceSlot = i;
                mace = stack;
            }
        }

        double fall = player.fallDistance;
        boolean smashReady = fall > MIN_SMASH_FALL && !player.isFallFlying();

        List<Line> lines = new ArrayList<>();

        // The combo cue: lunge/fall with the spear, then swap to the mace to smash.
        if (holdingSpear && smashReady && maceSlot >= 0) {
            lines.add(new Line(">> SWAP TO MACE [" + (maceSlot + 1) + "] <<", ORANGE));
            lines.add(new Line("smash ~" + fmt(smashDamage(mace, fall)) + " dmg", ORANGE));
        }

        if (spearSlot >= 0) {
            lines.add(new Line("Spear [" + (spearSlot + 1) + "]" + (lunge > 0 ? "  Lunge " + roman(lunge) : ""), WHITE));
            if (holdingSpear) {
                float charge = player.getAttackStrengthScale(0.0F);
                if (charge >= 1.0F) {
                    lines.add(new Line("  jab READY", GREEN));
                } else {
                    lines.add(new Line("  jab " + (int) (charge * 100) + "%", YELLOW));
                }
            }
            if (lunge > 0) {
                var food = player.getFoodData();
                int foodLevel = food.getFoodLevel();
                if (foodLevel <= 6) {
                    lines.add(new Line("  can't lunge: food " + foodLevel + " (needs 7+)", RED));
                } else {
                    double spendable = (foodLevel - 6) + food.getSaturationLevel();
                    int lunges = Math.max(1, (int) (spendable / lunge));
                    int color = lunges >= 6 ? GREEN : (lunges >= 3 ? YELLOW : RED);
                    lines.add(new Line("  ~" + lunges + " lunges of food left", color));
                }
            }
        }

        if (maceSlot >= 0) {
            lines.add(new Line("Mace [" + (maceSlot + 1) + "]", WHITE));
            if (smashReady) {
                lines.add(new Line("  smash ~" + fmt(smashDamage(mace, fall)) + " dmg", GREEN));
            } else {
                lines.add(new Line("  fall 1.5+ blocks to smash", GREY));
            }
        }

        if (lines.isEmpty()) {
            return;
        }

        Font font = mc.font;
        int width = 0;
        for (Line line : lines) {
            width = Math.max(width, font.width(line.text()));
        }
        int lineHeight = 10;
        int height = lines.size() * lineHeight;
        int x = graphics.guiWidth() / 2 + 24;
        int y = graphics.guiHeight() / 2 - height / 2;

        graphics.fill(x - 3, y - 3, x + width + 3, y + height + 1, 0x80000000);
        int lineY = y;
        for (Line line : lines) {
            graphics.drawString(font, line.text(), x, lineY, line.color());
            lineY += lineHeight;
        }
    }

    private static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_spear");
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
