package com.lansync.server;

import com.formdev.flatlaf.FlatDarkLaf;
import com.lansync.common.FileMetadata;
import com.lansync.common.Utils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Giao diện đồ họa cho Server.
 * Hiển thị: danh sách client đang kết nối, danh sách file, log hoạt động.
 */
public class ServerGUI extends JFrame {
    // ===== MÀU SẮC THEME =====
    private static final Color BG_DARK     = new Color(18, 20, 28);
    private static final Color BG_PANEL    = new Color(26, 30, 42);
    private static final Color BG_CARD     = new Color(33, 38, 54);
    private static final Color ACCENT      = new Color(99, 179, 237);
    private static final Color ACCENT2     = new Color(154, 117, 234);
    private static final Color SUCCESS     = new Color(72, 199, 142);
    private static final Color WARNING     = new Color(255, 190, 74);
    private static final Color ERROR_COLOR = new Color(245, 101, 101);
    private static final Color TEXT_MAIN   = new Color(237, 242, 247);
    private static final Color TEXT_SUB    = new Color(160, 174, 192);

    // ===== COMPONENTS =====
    private JLabel statusLabel;
    private JLabel clientCountLabel;
    private JTable clientTable;
    private DefaultTableModel clientTableModel;
    private JTable fileTable;
    private DefaultTableModel fileTableModel;
    private JTextArea logArea;
    private JButton startStopButton;
    private JTextField portField;
    private JLabel serverIpLabel;

    private SyncServer server;
    private Thread serverThread;
    private volatile boolean serverRunning = false;

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss");

    public ServerGUI() {
        setTitle("LAN File Sync — Server Dashboard");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1100, 750);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmAndExit();
            }
        });

        initComponents();
        applyTheme();
        detectLocalIp();
        setVisible(true);
    }

    // ===================================================
    //  KHỞI TẠO GIAO DIỆN
    // ===================================================

    private void initComponents() {
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(BG_DARK);

        // Header
        add(createHeaderPanel(), BorderLayout.NORTH);

        // Center: chia 3 panel
        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            createLeftPanel(), createCenterRightPanel());
        centerSplit.setDividerLocation(260);
        centerSplit.setDividerSize(4);
        centerSplit.setBorder(null);
        centerSplit.setBackground(BG_DARK);
        add(centerSplit, BorderLayout.CENTER);

        // Bottom: log
        add(createLogPanel(), BorderLayout.SOUTH);
    }

    // === HEADER ===
    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BG_PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 70, 95)),
            new EmptyBorder(14, 20, 14, 20)
        ));

        // Logo + Title
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        titlePanel.setBackground(BG_PANEL);
        JLabel icon = new JLabel("⚡");
        icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 28));
        JLabel title = new JLabel("LAN File Sync");
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(TEXT_MAIN);
        JLabel subtitle = new JLabel("  Server Dashboard");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        subtitle.setForeground(TEXT_SUB);
        titlePanel.add(icon);
        titlePanel.add(title);
        titlePanel.add(subtitle);
        header.add(titlePanel, BorderLayout.WEST);

        // Controls: port + start/stop + status
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        controlPanel.setBackground(BG_PANEL);

        serverIpLabel = new JLabel("IP: đang tải...");
        serverIpLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        serverIpLabel.setForeground(TEXT_SUB);

        JLabel portLabel = new JLabel("Cổng:");
        portLabel.setForeground(TEXT_SUB);
        portField = new JTextField(String.valueOf(Utils.SERVER_PORT), 6);
        portField.setBackground(BG_CARD);
        portField.setForeground(TEXT_MAIN);
        portField.setCaretColor(TEXT_MAIN);
        portField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 70, 95)),
            new EmptyBorder(4, 8, 4, 8)
        ));

        startStopButton = createButton("▶  Khởi động", SUCCESS);
        startStopButton.addActionListener(e -> toggleServer());

        statusLabel = new JLabel("⚫  Chờ khởi động");
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        statusLabel.setForeground(TEXT_SUB);

        controlPanel.add(serverIpLabel);
        controlPanel.add(Box.createHorizontalStrut(15));
        controlPanel.add(portLabel);
        controlPanel.add(portField);
        controlPanel.add(startStopButton);
        controlPanel.add(Box.createHorizontalStrut(10));
        controlPanel.add(statusLabel);
        header.add(controlPanel, BorderLayout.EAST);

        return header;
    }

    // === LEFT: Clients ===
    private JPanel createLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_PANEL);
        panel.setBorder(new EmptyBorder(10, 10, 10, 5));

        // Title
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(BG_PANEL);
        titleBar.setBorder(new EmptyBorder(0, 0, 8, 0));
        JLabel title = new JLabel("👥  Clients Đang Kết Nối");
        title.setFont(new Font("Segoe UI", Font.BOLD, 14));
        title.setForeground(TEXT_MAIN);
        clientCountLabel = new JLabel("0");
        clientCountLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        clientCountLabel.setForeground(ACCENT);
        titleBar.add(title, BorderLayout.WEST);
        titleBar.add(clientCountLabel, BorderLayout.EAST);
        panel.add(titleBar, BorderLayout.NORTH);

        // Client table
        String[] clientCols = {"Username", "IP", "Trạng thái"};
        clientTableModel = new DefaultTableModel(clientCols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        clientTable = createStyledTable(clientTableModel);
        clientTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        clientTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        clientTable.getColumnModel().getColumn(2).setPreferredWidth(70);

        JScrollPane scrollPane = new JScrollPane(clientTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(50, 60, 85)));
        scrollPane.getViewport().setBackground(BG_CARD);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Refresh button
        JButton refreshBtn = createButton("🔄  Làm mới", ACCENT);
        refreshBtn.addActionListener(e -> refreshFileList());
        panel.add(refreshBtn, BorderLayout.SOUTH);

        return panel;
    }

    // === CENTER + RIGHT: Files ===
    private JPanel createCenterRightPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_PANEL);
        panel.setBorder(new EmptyBorder(10, 5, 10, 10));

        // Title
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(BG_PANEL);
        titleBar.setBorder(new EmptyBorder(0, 0, 8, 0));
        JLabel title = new JLabel("📁  Tất Cả File Trong Hệ Thống");
        title.setFont(new Font("Segoe UI", Font.BOLD, 14));
        title.setForeground(TEXT_MAIN);
        titleBar.add(title, BorderLayout.WEST);
        panel.add(titleBar, BorderLayout.NORTH);

        // File table
        String[] fileCols = {"Tên File", "Kích Thước", "Phiên Bản", "Chủ Sở Hữu", "Cập Nhật Lần Cuối", "SHA-256"};
        fileTableModel = new DefaultTableModel(fileCols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        fileTable = createStyledTable(fileTableModel);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(90);
        fileTable.getColumnModel().getColumn(4).setPreferredWidth(130);
        fileTable.getColumnModel().getColumn(5).setPreferredWidth(100);

        JScrollPane scrollPane = new JScrollPane(fileTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(50, 60, 85)));
        scrollPane.getViewport().setBackground(BG_CARD);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    // === BOTTOM: Log ===
    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(50, 60, 85)),
            new EmptyBorder(8, 10, 8, 10)
        ));
        panel.setPreferredSize(new Dimension(0, 180));

        JLabel title = new JLabel("📋  Nhật Ký Hoạt Động");
        title.setFont(new Font("Segoe UI", Font.BOLD, 13));
        title.setForeground(TEXT_MAIN);
        title.setBorder(new EmptyBorder(0, 0, 5, 0));
        panel.add(title, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setBackground(new Color(13, 15, 22));
        logArea.setForeground(new Color(134, 239, 172));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBorder(new EmptyBorder(5, 8, 5, 8));

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createLineBorder(new Color(40, 50, 70)));
        panel.add(logScroll, BorderLayout.CENTER);

        JButton clearBtn = createSmallButton("Xóa log", TEXT_SUB);
        clearBtn.addActionListener(e -> logArea.setText(""));
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        btnPanel.setBackground(BG_PANEL);
        btnPanel.add(clearBtn);
        panel.add(btnPanel, BorderLayout.EAST);

        return panel;
    }

    // ===================================================
    //  LOGIC SERVER
    // ===================================================

    private void toggleServer() {
        if (!serverRunning) {
            startServer();
        } else {
            stopServer();
        }
    }

    private void startServer() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1024 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this,
                "Cổng không hợp lệ! Vui lòng nhập số từ 1024 đến 65535.",
                "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        portField.setEnabled(false);
        startStopButton.setEnabled(false);

        server = new SyncServer(port);
        server.setLogListener(this::appendLog);
        server.setClientEventListener(this::onClientEvent);

        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("❌ Lỗi server: " + e.getMessage());
                    onServerStopped();
                });
            }
        }, "ServerThread");
        serverThread.setDaemon(true);
        serverThread.start();

        serverRunning = true;
        startStopButton.setEnabled(true);
        startStopButton.setText("⏹  Dừng Server");
        startStopButton.setBackground(ERROR_COLOR);
        statusLabel.setText("🟢  Đang chạy — cổng " + port);
        statusLabel.setForeground(SUCCESS);
        appendLog("🚀 Server khởi động tại cổng " + port);
    }

    private void stopServer() {
        if (server != null) {
            new Thread(() -> {
                server.stop();
                SwingUtilities.invokeLater(this::onServerStopped);
            }).start();
        }
    }

    private void onServerStopped() {
        serverRunning = false;
        portField.setEnabled(true);
        startStopButton.setText("▶  Khởi động");
        startStopButton.setBackground(SUCCESS);
        statusLabel.setText("⚫  Đã dừng");
        statusLabel.setForeground(TEXT_SUB);
        clientTableModel.setRowCount(0);
        fileTableModel.setRowCount(0);
        clientCountLabel.setText("0");
    }

    // ===================================================
    //  CẬP NHẬT UI
    // ===================================================

    /**
     * Được gọi khi client connect/disconnect.
     */
    private void onClientEvent(String clientId, boolean connected) {
        SwingUtilities.invokeLater(() -> {
            if (connected) {
                clientTableModel.addRow(new Object[]{
                    clientId,
                    getClientAddress(clientId),
                    "🟢 Online"
                });
            } else {
                removeClientRow(clientId);
            }
            clientCountLabel.setText(String.valueOf(clientTableModel.getRowCount()));
            refreshFileList();
        });
    }

    private void refreshFileList() {
        if (server == null) return;
        SwingUtilities.invokeLater(() -> {
            fileTableModel.setRowCount(0);
            List<FileMetadata> files = server.getMetadataManager().getAllCurrentFiles();
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yy HH:mm:ss");
            for (FileMetadata f : files) {
                String hash = f.getSha256() != null
                    ? f.getSha256().substring(0, Math.min(12, f.getSha256().length())) + "..."
                    : "-";
                fileTableModel.addRow(new Object[]{
                    f.getFilename(),
                    f.getReadableSize(),
                    "v" + f.getVersion(),
                    f.getOwnerClient() != null ? f.getOwnerClient() : "-",
                    sdf.format(new Date(f.getLastModified())),
                    hash
                });
            }
        });
    }

    private void removeClientRow(String clientId) {
        for (int i = clientTableModel.getRowCount() - 1; i >= 0; i--) {
            if (clientId.equals(clientTableModel.getValueAt(i, 0))) {
                clientTableModel.removeRow(i);
                break;
            }
        }
    }

    private String getClientAddress(String clientId) {
        if (server == null) return "N/A";
        return server.getClients().stream()
            .filter(h -> clientId.equals(h.getClientId()))
            .map(h -> h.getClientAddress())
            .findFirst().orElse("N/A");
    }

    /**
     * Thêm dòng vào log area.
     */
    public void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            String time = TIME_FMT.format(new Date());
            logArea.append("[" + time + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ===================================================
    //  TIỆN ÍCH UI
    // ===================================================

    private void applyTheme() {
        getContentPane().setBackground(BG_DARK);
    }

    private JButton createButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setBorder(new EmptyBorder(8, 18, 8, 18));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setOpaque(true);
        return btn;
    }

    private JButton createSmallButton(String text, Color fg) {
        JButton btn = new JButton(text);
        btn.setBackground(BG_CARD);
        btn.setForeground(fg);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btn.setBorder(new EmptyBorder(4, 10, 4, 10));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JTable createStyledTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setBackground(BG_CARD);
        table.setForeground(TEXT_MAIN);
        table.setGridColor(new Color(50, 60, 85));
        table.setRowHeight(30);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        table.setSelectionBackground(new Color(60, 80, 120));
        table.setSelectionForeground(Color.WHITE);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setBackground(BG_PANEL);
        table.getTableHeader().setForeground(TEXT_SUB);
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        table.getTableHeader().setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 70, 95)));
        return table;
    }

    private void detectLocalIp() {
        new Thread(() -> {
            try {
                java.net.InetAddress localHost = java.net.InetAddress.getLocalHost();
                SwingUtilities.invokeLater(() ->
                    serverIpLabel.setText("IP: " + localHost.getHostAddress()));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                    serverIpLabel.setText("IP: Không xác định"));
            }
        }).start();
    }

    private void confirmAndExit() {
        if (serverRunning) {
            int choice = JOptionPane.showConfirmDialog(
                this,
                "Server đang chạy. Bạn có muốn dừng server và thoát?",
                "Xác nhận thoát",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            if (choice != JOptionPane.YES_OPTION) return;
            stopServer();
        }
        dispose();
        System.exit(0);
    }

    // ===================================================
    //  MAIN (entry point cho ServerGUI standalone)
    // ===================================================

    public static void main(String[] args) {
        try {
            FlatDarkLaf.setup();
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 8);
            UIManager.put("TextComponent.arc", 6);
        } catch (Exception e) {
            // Fallback to default LAF
        }
        SwingUtilities.invokeLater(ServerGUI::new);
    }
}
