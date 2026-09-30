package com.lansync.server;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;

/**
 * Entry point cho Server application.
 */
public class ServerMain {
    public static void main(String[] args) {
        // Áp dụng FlatLaf Dark theme
        try {
            FlatDarkLaf.setup();
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 8);
            UIManager.put("TextComponent.arc", 6);
            UIManager.put("ScrollBar.width", 10);
        } catch (Exception e) {
            System.err.println("Không thể tải FlatLaf theme: " + e.getMessage());
        }

        // Khởi động GUI trên Event Dispatch Thread
        SwingUtilities.invokeLater(() -> {
            ServerGUI gui = new ServerGUI();
            gui.appendLog("🔧 LAN File Sync Server v1.0.0 — Sẵn sàng");
            gui.appendLog("ℹ️  Nhấn 'Khởi động' để bắt đầu lắng nghe kết nối.");
        });
    }
}
