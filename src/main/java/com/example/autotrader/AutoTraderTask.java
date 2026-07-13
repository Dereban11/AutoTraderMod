package com.example.autotrader;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.MerchantOffer;
import net.minecraft.village.MerchantOfferList;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoTraderTask {
    private final MinecraftClient client;
    private final IBaritone baritone;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile State currentState = State.BUY_EMERALDS;

    private int emeraldStacks = 0;
    private boolean firstClickDone = false;
    private BlockPos workbenchPos;
    private BlockPos chestPos;
    private int waitTicks = 0;
    private long lastTradeCheckTime = 0;

    private List<BlockPos> villagersToTrade = new ArrayList<>();
    private int currentVillagerIndex = 0;

    public AutoTraderTask() {
        this.client = MinecraftClient.getInstance();
        this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public void start() {
        running.set(true);
        new Thread(this::runLoop).start();
    }

    public void stop() {
        running.set(false);
        baritone.getPathingBehavior().forceCancel();
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                if (needToEatOrDrink()) {
                    handleEatOrDrink();
                    continue;
                }

                switch (currentState) {
                    case BUY_EMERALDS:
                        buyEmeralds();
                        break;
                    case TRADE_WITH_VILLAGERS:
                        tradeWithVillagers();
                        break;
                    case CRAFT_BLOCKS:
                        craftAndStoreBlocks();
                        break;
                    case WAIT_FOR_TRADES:
                        waitForTrades();
                        break;
                }
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                break;
            } catch (Exception e) {
                e.printStackTrace();
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    // ---------------- СОСТОЯНИЯ ----------------

    private void buyEmeralds() {
        if (emeraldStacks >= 10) {
            currentState = State.TRADE_WITH_VILLAGERS;
            return;
        }

        if (!(client.currentScreen instanceof HandledScreen)) {
            client.player.sendCommand("shop");
            waitForGui(500);
            return;
        }

        if (!firstClickDone) {
            clickSlot1Based(22, 0);
            firstClickDone = true;
            return;
        }

        clickSlot1Based(24, 1);
        clickSlot1Based(3, 1);
        emeraldStacks++;

        if (emeraldStacks >= 10) {
            client.player.closeHandledScreen();
            firstClickDone = false;
            currentState = State.TRADE_WITH_VILLAGERS;
        }
    }

    private void tradeWithVillagers() {
        if (countItemStacks(Items.GOLD_INGOT) >= 20) {
            currentState = State.CRAFT_BLOCKS;
            return;
        }

        if (villagersToTrade.isEmpty()) {
            findVillagers();
            if (villagersToTrade.isEmpty()) {
                currentState = State.WAIT_FOR_TRADES;
                return;
            }
            currentVillagerIndex = 0;
        }

        if (currentVillagerIndex >= villagersToTrade.size()) {
            currentState = State.WAIT_FOR_TRADES;
            return;
        }

        BlockPos target = villagersToTrade.get(currentVillagerIndex);
        if (!baritone.getPathingBehavior().isActive() || !baritone.getGoal().isInGoal(target.up())) {
            baritone.getCustomGoalProcess().setGoal(new GoalBlock(target.up()));
            return;
        }

        if (!(client.currentScreen instanceof MerchantScreen)) {
            client.execute(() -> {
                VillagerEntity villager = findVillagerAt(target);
                if (villager != null) {
                    client.interactionManager.interactEntity(client.player, villager, Hand.MAIN_HAND);
                }
            });
            waitForGui(500);
            return;
        }

        MerchantScreen screen = (MerchantScreen) client.currentScreen;
        MerchantOfferList offers = screen.getOffers();
        boolean traded = false;
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            if (!offer.isDisabled() && hasEnoughItemsForTrade(offer)) {
                clickTradeSlot(i);
                traded = true;
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                break;
            }
        }

        if (traded) return;
        currentVillagerIndex++;
    }

    private void craftAndStoreBlocks() {
        if (workbenchPos == null) {
            workbenchPos = findNearestWorkbench();
            if (workbenchPos == null) {
                currentState = State.TRADE_WITH_VILLAGERS;
                return;
            }
        }

        if (!(client.currentScreen instanceof CraftingScreen)) {
            if (!baritone.getGoal().isInGoal(workbenchPos.up())) {
                baritone.getCustomGoalProcess().setGoal(new GoalBlock(workbenchPos.up()));
                return;
            }
            client.execute(() -> {
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, workbenchPos);
            });
            waitForGui(500);
            return;
        }

        int totalIngots = countItems(Items.GOLD_INGOT);
        int fullCrafts = totalIngots / 9;

        for (int i = 0; i < fullCrafts; i++) {
            craftOneBlock();
        }

        client.player.closeHandledScreen();

        if (chestPos == null) {
            chestPos = findChestWithLabel("золотой блок");
            if (chestPos == null) {
                currentState = State.TRADE_WITH_VILLAGERS;
                return;
            }
        }
        storeAllGoldBlocks();
        currentState = State.TRADE_WITH_VILLAGERS;
    }

    private void waitForTrades() {
        long now = System.currentTimeMillis();
        if (waitTicks == 0) {
            waitTicks = 5 * 60 * 20;
            lastTradeCheckTime = now;
        }

        if (now - lastTradeCheckTime >= 5 * 60 * 1000) {
            if (haveTradesUpdated()) {
                villagersToTrade.clear();
                currentState = State.TRADE_WITH_VILLAGERS;
                waitTicks = 0;
                return;
            } else {
                lastTradeCheckTime = now + 3 * 60 * 1000;
            }
        }
    }

    // ---------------- ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ----------------

    private boolean needToEatOrDrink() {
        if (client.player == null) return false;
        if (client.player.getHealth() <= 13 && hasItem(Items.GOLDEN_CARROT)) return true;
        if (!client.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.INVISIBILITY) && hasItem(Items.POTION)) return true;
        return false;
    }

    private void handleEatOrDrink() {
        if (client.currentScreen != null) {
            client.player.closeHandledScreen();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }

        if (client.player.getHealth() <= 13 && hasItem(Items.GOLDEN_CARROT)) {
            useItem(Items.GOLDEN_CARROT);
        } else if (hasItem(Items.POTION)) {
            useItem(Items.POTION);
        }
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
    }

    private void useItem(Item item) {
        client.execute(() -> {
            int slot = findSlotWithItem(item);
            if (slot != -1) {
                client.player.getInventory().selectedSlot = slot;
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            }
        });
    }

    private void clickSlot1Based(int slot1Based, int button) {
        clickSlot(slot1Based - 1, button);
    }

    private void clickSlot(int slotIndex, int button) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) return;
        ScreenHandler handler = screen.getScreenHandler();
        client.execute(() -> {
            client.interactionManager.clickSlot(handler.syncId, handler.getRevision(), slotIndex, button, SlotActionType.PICKUP, client.player);
        });
    }

    private void clickTradeSlot(int offerIndex) {
        clickSlot(offerIndex, 0);
    }

    private boolean hasItem(Item item) {
        for (ItemStack stack : client.player.getInventory().main) {
            if (!stack.isEmpty() && stack.isOf(item)) return true;
        }
        return false;
    }

    private int findSlotWithItem(Item item) {
        for (int i = 0; i < client.player.getInventory().main.size(); i++) {
            ItemStack stack = client.player.getInventory().main.get(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private int countItems(Item item) {
        int count = 0;
        for (ItemStack stack : client.player.getInventory().main) {
            if (!stack.isEmpty() && stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countItemStacks(Item item) {
        return countItems(item) / 64;
    }

    private boolean hasEnoughItemsForTrade(MerchantOffer offer) {
        ItemStack firstInput = offer.getFirstBuyItem();
        if (firstInput.isOf(Items.EMERALD)) {
            return countItems(Items.EMERALD) >= firstInput.getCount();
        }
        return false;
    }

    private BlockPos findNearestWorkbench() {
        World world = client.world;
        if (world == null) return null;
        BlockPos playerPos = client.player.getBlockPos();
        for (int dx = -10; dx <= 10; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -10; dz <= 10; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private BlockPos findChestWithLabel(String label) {
        World world = client.world;
        if (world == null) return null;
        BlockPos playerPos = client.player.getBlockPos();
        for (int dx = -20; dx <= 20; dx++) {
            for (int dy = -10; dy <= 10; dy++) {
                for (int dz = -20; dz <= 20; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.CHEST)) {
                        for (int sx = -1; sx <= 1; sx++) {
                            for (int sy = -1; sy <= 1; sy++) {
                                for (int sz = -1; sz <= 1; sz++) {
                                    BlockPos signPos = pos.add(sx, sy, sz);
                                    if (world.getBlockState(signPos).isOf(net.minecraft.block.Blocks.OAK_SIGN)) {
                                        return pos;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private void findVillagers() {
        villagersToTrade.clear();
        World world = client.world;
        if (world == null) return;
        List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, client.player.getBoundingBox().expand(10), e -> true);
        for (VillagerEntity v : villagers) {
            villagersToTrade.add(v.getBlockPos());
        }
    }

    private VillagerEntity findVillagerAt(BlockPos pos) {
        World world = client.world;
        if (world == null) return null;
        List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, new net.minecraft.util.math.Box(pos).expand(2), e -> e.getBlockPos().equals(pos));
        return villagers.isEmpty() ? null : villagers.get(0);
    }

    private boolean haveTradesUpdated() {
        if (villagersToTrade.isEmpty()) return false;
        BlockPos pos = villagersToTrade.get(0);
        VillagerEntity villager = findVillagerAt(pos);
        if (villager == null) return false;
        MerchantOfferList offers = villager.getOffers();
        for (MerchantOffer offer : offers) {
            if (!offer.isDisabled()) {
                return true;
            }
        }
        return false;
    }

    private void craftOneBlock() {
        if (!(client.currentScreen instanceof CraftingScreen)) return;
        int sourceSlot = findSlotWithItem(Items.GOLD_INGOT);
        if (sourceSlot == -1) return;

        clickSlot(sourceSlot, 0);
        for (int j = 1; j <= 9; j++) {
            clickSlot(j, 0);
        }
        clickSlot(0, 0);
        clickSlot(sourceSlot, 0);
    }

    private void storeAllGoldBlocks() {
        if (chestPos == null) return;
        if (!baritone.getGoal().isInGoal(chestPos.up())) {
            baritone.getCustomGoalProcess().setGoal(new GoalBlock(chestPos.up()));
            return;
        }
        if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.GenericContainerScreen)) {
            client.execute(() -> {
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, chestPos);
            });
            waitForGui(500);
            return;
        }
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.GOLD_BLOCK)) {
                client.execute(() -> {
                    client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId,
                            client.player.currentScreenHandler.getRevision(),
                            i,
                            0,
                            SlotActionType.QUICK_MOVE,
                            client.player
                    );
                });
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }
        client.player.closeHandledScreen();
    }

    private void waitForGui(int timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs && client.currentScreen == null) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
    }

    private enum State {
        BUY_EMERALDS,
        TRADE_WITH_VILLAGERS,
        CRAFT_BLOCKS,
        WAIT_FOR_TRADES
    }
}