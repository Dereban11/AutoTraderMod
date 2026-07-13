package com.example.autotrader;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

public class AutoTraderMod implements ClientModInitializer {
    private static AutoTraderTask task;
    private static boolean running = false;
    private static Thread inputThread;

    @Override
    public void onInitializeClient() {
        System.out.println("AutoTrader initialized. Press B to toggle.");
        // Запускаем поток для проверки клавиши B
        inputThread = new Thread(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            while (true) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }
                if (client.getWindow() != null && GLFW.glfwGetKey(client.getWindow().getHandle(), GLFW.GLFW_KEY_B) == GLFW.GLFW_PRESS) {
                    // Нажата B — переключаем
                    toggleTask();
                    // Небольшая задержка, чтобы избежать множественных срабатываний
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        });
        inputThread.setDaemon(true);
        inputThread.start();
    }

    public static void toggleTask() {
        if (task != null && task.isRunning()) {
            task.stop();
            task = null;
            System.out.println("AutoTrader stopped.");
        } else {
            if (task == null) {
                task = new AutoTraderTask();
            }
            task.start();
            System.out.println("AutoTrader started.");
        }
    }
}