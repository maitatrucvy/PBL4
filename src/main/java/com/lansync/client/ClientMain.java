package com.lansync.client;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;

/**
 * Entry point cho Client application.
 */
public class ClientMain {
    public static void main(String[] args) {
        // Áp dụng FlatLaf Dark theme
        try {
            FlatDarkLaf.setup();
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 8);
            UIManager.put("TextComponent.arc", 6);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("TabbedPane.selectedBackground", new java.awt.Color(40, 50, 75));
        } catch (Exception e) {
            System.err.println("Không thể tải FlatLaf theme: " + e.getMessage());
        }

        // Khởi động GUI trên Event Dispatch Thread
        SwingUtilities.invokeLater(ClientGUI::new);
    }
}
