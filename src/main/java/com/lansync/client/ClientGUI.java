package com.lansync.client;

import com.formdev.flatlaf.FlatDarkLaf;
import com.lansync.common.FileMetadata;
import com.lansync.common.Message;
import com.lansync.common.MessageType;
import com.lansync.common.Utils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Giao diện đồ họa đầy đủ cho Client.
 * Gồm: Login/Register dialog → Main dashboard
 */
public class ClientGUI extends JFrame {
    private static final Logger LOGGER = Logger.getLogger(ClientGUI.class.getName());

    // ===== MÀU SẮC THEME =====
    private static final Color BG_DARK     = new Color(15, 17, 26);
    private static final Color BG_PANEL    = new Color(22, 26, 38);
    private static final Color BG_CARD     = new Color(30, 35, 52);
    private static final Color BG_TABLE    = new Color(26, 31, 46);
    private static final Color ACCENT      = new Color(99, 179, 237);
    private static final Color ACCENT2     = new Color(154, 117, 234);
    private static final Color SUCCESS     = new Color(72, 199, 142);
    private static final Color WARNING     = new Color(255, 190, 74);
    private static final Color ERROR_COLOR = new Color(245, 101, 101);
    private static final Color TEXT_MAIN   = new Color(237, 242, 247);
    private static final Color TEXT_SUB    = new Color(160, 174, 192);
    private static final Color BORDER_COL  = new Color(50, 60, 85);

    // ===== FONTS =====
    private static final Font FONT_TITLE  = new Font("Segoe UI", Font.BOLD, 22);
    private static final Font FONT_HEADER = new Font("Segoe UI", Font.BOLD, 14);
    private static final Font FONT_BODY   = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font FONT_MONO   = new Font("Consolas", Font.PLAIN, 12);

    // ===== COMPONENTS =====
    private JTable fileTable;
    private DefaultTableModel fileTableModel;
    private TableRowSorter<DefaultTableModel> tableSorter;
    private JTextField searchField;
    private JTextArea logArea;
    private JLabel statusLabel;
    private JLabel connLabel;
    private JLabel userLabel;
    private JLabel syncDirLabel;
    private JProgressBar progressBar;

    // Stats panel
    private JLabel totalFilesLabel;
    private JLabel totalSizeLabel;
    private JLabel lastSyncLabel;

    // ===== CORE =====
    private SyncClient syncClient;
    private SyncEngine syncEngine;
    private FileWatcher fileWatcher;

    private String syncDirPath = System.getProperty("user.home") + File.separator + "LanSyncFolder";
    private String serverHost = "localhost";
    private int    serverPort = Utils.SERVER_PORT;
    private String username;

    /** Trạng thái sync của từng file */
    private final Map<String, SyncEngine.SyncStatus> fileStatusMap = new ConcurrentHashMap<>();

    private static final SimpleDateFormat TIME_FMT  = new SimpleDateFormat("HH:mm:ss");
    private static final SimpleDateFormat DATE_FMT  = new SimpleDateFormat("dd/MM/yy HH:mm");

    // ===================================================
    //  KHỞI TẠO
    // ===================================================

    public ClientGUI() {
        setTitle("LAN File Sync — Client");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1100, 720);
        setMinimumSize(new Dimension(850, 580));
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { confirmAndExit(); }
        });

        // Khởi tạo UI chính (ẩn)
        initMainUI();
        setVisible(false);

        // Hiển thị dialog đăng nhập
        showConnectDialog();
    }

    // ===================================================
    //  DIALOG KẾT NỐI + ĐĂNG NHẬP
    // ===================================================

    private void showConnectDialog() {
        JDialog dialog = new JDialog(this, "LAN File Sync — Kết Nối", true);
        dialog.setSize(480, 580);
        dialog.setLocationRelativeTo(null);
        dialog.setResizable(false);
        dialog.getContentPane().setBackground(BG_DARK);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBackground(BG_DARK);
        mainPanel.setBorder(new EmptyBorder(30, 40, 30, 40));

        // Logo / Icon
        JLabel logo = new JLabel("⚡", SwingConstants.CENTER);
        logo.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 52));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel title = new JLabel("LAN File Sync", SwingConstants.CENTER);
        title.setFont(FONT_TITLE);
        title.setForeground(TEXT_MAIN);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("Đồng bộ tập tin tự động qua mạng LAN", SwingConstants.CENTER);
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        subtitle.setForeground(TEXT_SUB);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        mainPanel.add(logo);
        mainPanel.add(Box.createVerticalStrut(5));
        mainPanel.add(title);
        mainPanel.add(Box.createVerticalStrut(4));
        mainPanel.add(subtitle);
        mainPanel.add(Box.createVerticalStrut(25));

        // Tab: Đăng nhập / Đăng ký
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(BG_PANEL);
        tabs.setForeground(TEXT_MAIN);
        tabs.setFont(FONT_BODY);
        tabs.add("Đăng Nhập", createLoginTab(dialog));
        tabs.add("Đăng Ký", createRegisterTab(dialog));
        mainPanel.add(tabs);

        dialog.add(mainPanel);
        dialog.setVisible(true);
    }

    private JPanel createLoginTab(JDialog dialog) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_PANEL);
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));

        // Server settings
        JLabel serverLabel = createLabel("🌐  Địa chỉ Server", TEXT_SUB);
        JTextField hostField = createTextField(serverHost);
        JLabel portLabel = createLabel("Cổng", TEXT_SUB);
        JTextField portField = createTextField(String.valueOf(serverPort));
        JLabel dirLabel = createLabel("📁  Thư mục đồng bộ", TEXT_SUB);
        JPanel dirPanel = new JPanel(new BorderLayout(5, 0));
        dirPanel.setBackground(BG_PANEL);
        JTextField dirField = createTextField(syncDirPath);
        JButton browseBtn = createSmallButton("Duyệt...", ACCENT);
        browseBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(syncDirPath);
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                dirField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        dirPanel.add(dirField, BorderLayout.CENTER);
        dirPanel.add(browseBtn, BorderLayout.EAST);

        // Separator
        JSeparator sep = new JSeparator();
        sep.setForeground(BORDER_COL);

        JLabel credLabel = createLabel("👤  Thông tin đăng nhập", TEXT_SUB);
        JTextField usernameField = createTextField("");
        usernameField.setToolTipText("Tài khoản mặc định: admin / admin123");
        JPasswordField passwordField = new JPasswordField();
        styleTextField(passwordField);

        JButton loginBtn = createButton("Đăng Nhập  →", ACCENT, Color.WHITE);
        loginBtn.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel errorLabel = new JLabel(" ", SwingConstants.CENTER);
        errorLabel.setFont(FONT_BODY);
        errorLabel.setForeground(ERROR_COLOR);
        errorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Thêm vào panel
        panel.add(serverLabel); panel.add(Box.createVerticalStrut(4));
        panel.add(hostField); panel.add(Box.createVerticalStrut(8));
        panel.add(portLabel); panel.add(Box.createVerticalStrut(4));
        panel.add(portField); panel.add(Box.createVerticalStrut(8));
        panel.add(dirLabel); panel.add(Box.createVerticalStrut(4));
        panel.add(dirPanel); panel.add(Box.createVerticalStrut(12));
        panel.add(sep); panel.add(Box.createVerticalStrut(12));
        panel.add(credLabel); panel.add(Box.createVerticalStrut(4));
        panel.add(createLabel("Username", TEXT_SUB)); panel.add(Box.createVerticalStrut(4));
        panel.add(usernameField); panel.add(Box.createVerticalStrut(8));
        panel.add(createLabel("Password", TEXT_SUB)); panel.add(Box.createVerticalStrut(4));
        panel.add(passwordField); panel.add(Box.createVerticalStrut(16));
        panel.add(loginBtn); panel.add(Box.createVerticalStrut(8));
        panel.add(errorLabel);

        // Xử lý đăng nhập
        loginBtn.addActionListener(e -> {
            String host = hostField.getText().trim();
            String portStr = portField.getText().trim();
            String dir = dirField.getText().trim();
            String user = usernameField.getText().trim();
            String pass = new String(passwordField.getPassword());

            if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                errorLabel.setText("Vui lòng điền đầy đủ thông tin!");
                return;
            }
            int port;
            try { port = Integer.parseInt(portStr); } catch (NumberFormatException ex) {
                errorLabel.setText("Cổng không hợp lệ!");
                return;
            }

            loginBtn.setEnabled(false);
            loginBtn.setText("Đang kết nối...");
            errorLabel.setText(" ");

            serverHost  = host;
            serverPort  = port;
            syncDirPath = dir.isEmpty() ? syncDirPath : dir;
            username    = user;

            // Kết nối trong background
            new Thread(() -> {
                boolean success = connectToServer(user, pass);
                SwingUtilities.invokeLater(() -> {
                    if (success) {
                        dialog.dispose();
                        setVisible(true);
                    } else {
                        loginBtn.setEnabled(true);
                        loginBtn.setText("Đăng Nhập  →");
                        errorLabel.setText("Không thể kết nối hoặc sai tài khoản!");
                    }
                });
            }).start();
        });

        // Enter để đăng nhập
        passwordField.addActionListener(e -> loginBtn.doClick());

        return panel;
    }

    private JPanel createRegisterTab(JDialog dialog) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_PANEL);
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));

        JTextField regHostField   = createTextField("localhost");
        JTextField regPortField   = createTextField(String.valueOf(Utils.SERVER_PORT));
        JTextField regUserField   = createTextField("");
        JTextField regDisplayField = createTextField("");
        JPasswordField regPassField  = new JPasswordField();
        JPasswordField regPass2Field = new JPasswordField();
        styleTextField(regPassField);
        styleTextField(regPass2Field);

        JButton regBtn = createButton("Đăng Ký", ACCENT2, Color.WHITE);
        regBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel regMsg = new JLabel(" ", SwingConstants.CENTER);
        regMsg.setFont(FONT_BODY);
        regMsg.setAlignmentX(Component.CENTER_ALIGNMENT);

        panel.add(createLabel("🌐  Địa chỉ Server", TEXT_SUB)); panel.add(Box.createVerticalStrut(4));
        panel.add(regHostField); panel.add(Box.createVerticalStrut(8));
        panel.add(createLabel("Cổng", TEXT_SUB)); panel.add(Box.createVerticalStrut(4));
        panel.add(regPortField); panel.add(Box.createVerticalStrut(8));
        panel.add(createLabel("Username (≥ 3 ký tự)", TEXT_SUB)); panel.add(Box.createVerticalStrut(4));
        panel.add(regUserField); panel.add(Box.createVerticalStrut(8));
        panel.add(createLabel("Tên hiển thị", TEXT_SUB)); panel.add(Box.createVerticalStrut(4));
        panel.add(regDisplayField); panel.add(Box.createVerticalStrut(8));
        panel.add(createLabel("Mật khẩu (≥ 6 ký tự)", TEXT_SUB)); panel.add(Box.createVerticalStrut(4));
        panel.add(regPassField); panel.add(Box.createVerticalStrut(8));
        panel.add(createLabel("Xác nhận mật khẩu", TEXT_SUB)); panel.add(Box.createVerticalStrut(4));
        panel.add(regPass2Field); panel.add(Box.createVerticalStrut(16));
        panel.add(regBtn); panel.add(Box.createVerticalStrut(8));
        panel.add(regMsg);

        regBtn.addActionListener(e -> {
            String host  = regHostField.getText().trim();
            String user  = regUserField.getText().trim();
            String disp  = regDisplayField.getText().trim();
            String pass  = new String(regPassField.getPassword());
            String pass2 = new String(regPass2Field.getPassword());
            int port;
            try { port = Integer.parseInt(regPortField.getText().trim()); }
            catch (NumberFormatException ex) { regMsg.setForeground(ERROR_COLOR); regMsg.setText("Cổng không hợp lệ!"); return; }

            if (user.isEmpty() || pass.isEmpty()) {
                regMsg.setForeground(ERROR_COLOR); regMsg.setText("Điền đầy đủ thông tin!"); return; }
            if (!pass.equals(pass2)) {
                regMsg.setForeground(ERROR_COLOR); regMsg.setText("Mật khẩu không khớp!"); return; }

            regBtn.setEnabled(false);
            new Thread(() -> {
                SyncClient tempClient = new SyncClient(host, port);
                boolean[] result = new boolean[1];
                String[] resultMsg = new String[1];

                tempClient.setMessageListener(msg -> {
                    if (msg.getType() == MessageType.REGISTER_OK) {
                        result[0] = true;
                        resultMsg[0] = msg.getPayloadString("message");
                    } else {
                        resultMsg[0] = msg.getPayloadString("reason");
                    }
                    synchronized (result) { result.notify(); }
                });

                if (tempClient.connect()) {
                    new Thread(() -> { tempClient.startListening(); }).start();
                    tempClient.register(user, pass, disp.isEmpty() ? user : disp);
                    synchronized (result) {
                        try { result.wait(5000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    }
                    tempClient.disconnect();

                    String finalMsg = resultMsg[0];
                    boolean success = result[0];
                    SwingUtilities.invokeLater(() -> {
                        regBtn.setEnabled(true);
                        if (success) {
                            regMsg.setForeground(SUCCESS);
                            regMsg.setText(finalMsg != null ? finalMsg : "Đăng ký thành công!");
                        } else {
                            regMsg.setForeground(ERROR_COLOR);
                            regMsg.setText(finalMsg != null ? finalMsg : "Đăng ký thất bại!");
                        }
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        regBtn.setEnabled(true);
                        regMsg.setForeground(ERROR_COLOR);
                        regMsg.setText("Không thể kết nối tới server!");
                    });
                }
            }).start();
        });

        return panel;
    }

    // ===================================================
    //  KẾT NỐI VÀ KHỞI ĐỘNG ĐỒNG BỘ
    // ===================================================

    private boolean connectToServer(String user, String pass) {
        try {
            // Khởi tạo SyncClient
            syncClient = new SyncClient(serverHost, serverPort);

            // Kết nối
            if (!syncClient.connect()) return false;

            // Khởi tạo FileWatcher
            File syncDirFile = new File(syncDirPath);
            syncDirFile.mkdirs();
            fileWatcher = new FileWatcher(Paths.get(syncDirPath));

            // Khởi tạo SyncEngine
            syncEngine = new SyncEngine(syncClient, fileWatcher, Paths.get(syncDirPath));
            setupEngineListeners();

            // Bắt đầu lắng nghe server
            new Thread(() -> syncClient.startListening(), "ClientListenerThread").start();

            // Bắt đầu FileWatcher
            new Thread(() -> fileWatcher.start(), "FileWatcherThread").start();

            // Xử lý connection change
            syncClient.setConnectionListener(connected -> {
                SwingUtilities.invokeLater(() -> updateConnectionStatus(connected));
            });

            // Đăng nhập
            syncClient.login(user, pass);

            // Chờ phản hồi đăng nhập (tối đa 5 giây)
            Thread.sleep(500);
            if (!syncClient.isConnected()) return false;

            // Cập nhật UI
            username = user;
            SwingUtilities.invokeLater(() -> {
                userLabel.setText("👤 " + user);
                syncDirLabel.setText("📁 " + syncDirPath);
                updateConnectionStatus(true);
            });

            return true;
        } catch (Exception e) {
            LOGGER.severe("ClientGUI: Lỗi kết nối: " + e.getMessage());
            return false;
        }
    }

    private void setupEngineListeners() {
        syncEngine.setLogListener(msg -> SwingUtilities.invokeLater(() -> appendLog(msg)));
        syncEngine.setFileListListener(files -> SwingUtilities.invokeLater(() -> updateFileTable(files)));
        syncEngine.setStatusListener((filename, status) ->
            SwingUtilities.invokeLater(() -> {
                fileStatusMap.put(filename, status);
                updateFileTableStatus(filename, status);
            })
        );
        syncEngine.setConflictListener((conflictFile, reason) ->
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                    "<html><b>⚠️ Xung đột dữ liệu phát hiện!</b><br><br>" +
                    reason + "<br><br>" +
                    "File xung đột đã được lưu thành: <b>" + conflictFile + "</b></html>",
                    "Conflict", JOptionPane.WARNING_MESSAGE);
                appendLog("⚠️ CONFLICT: " + conflictFile + " — " + reason);
            })
        );
    }

    // ===================================================
    //  KHỞI TẠO UI CHÍNH
    // ===================================================

    private void initMainUI() {
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(BG_DARK);

        add(createTopBar(), BorderLayout.NORTH);

        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            createFilePanel(), createLogPanel());
        centerSplit.setDividerLocation(430);
        centerSplit.setDividerSize(4);
        centerSplit.setBorder(null);
        centerSplit.setBackground(BG_DARK);
        add(centerSplit, BorderLayout.CENTER);

        add(createStatusBar(), BorderLayout.SOUTH);
    }

    // ===== TOP BAR =====
    private JPanel createTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG_PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COL),
            new EmptyBorder(12, 18, 12, 18)
        ));

        // Left: title
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setBackground(BG_PANEL);
        JLabel icon = new JLabel("⚡");
        icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 24));
        JLabel title = new JLabel("LAN File Sync");
        title.setFont(FONT_TITLE);
        title.setForeground(TEXT_MAIN);
        left.add(icon);
        left.add(title);
        bar.add(left, BorderLayout.WEST);

        // Right: user + dir info
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setBackground(BG_PANEL);
        userLabel = new JLabel("👤 —");
        userLabel.setFont(FONT_BODY);
        userLabel.setForeground(ACCENT);
        syncDirLabel = new JLabel("📁 —");
        syncDirLabel.setFont(FONT_BODY);
        syncDirLabel.setForeground(TEXT_SUB);
        connLabel = new JLabel("⚫ Chưa kết nối");
        connLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        connLabel.setForeground(TEXT_SUB);

        JButton openDirBtn = createSmallButton("Mở thư mục", TEXT_SUB);
        openDirBtn.addActionListener(e -> openSyncDirectory());
        JButton disconnectBtn = createSmallButton("Ngắt kết nối", ERROR_COLOR);
        disconnectBtn.addActionListener(e -> disconnect());

        right.add(syncDirLabel);
        right.add(userLabel);
        right.add(connLabel);
        right.add(openDirBtn);
        right.add(disconnectBtn);
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    // ===== FILE PANEL =====
    private JPanel createFilePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(10, 12, 6, 12));

        // Title + stats
        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setBackground(BG_DARK);
        topRow.setBorder(new EmptyBorder(0, 0, 8, 0));

        JLabel title = new JLabel("📁  Danh Sách File Đồng Bộ");
        title.setFont(FONT_HEADER);
        title.setForeground(TEXT_MAIN);
        topRow.add(title, BorderLayout.WEST);

        // Stats
        JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        statsPanel.setBackground(BG_DARK);
        totalFilesLabel = createStatLabel("0 files");
        totalSizeLabel  = createStatLabel("0 B");
        lastSyncLabel   = createStatLabel("Chưa đồng bộ");
        statsPanel.add(totalFilesLabel);
        statsPanel.add(totalSizeLabel);
        statsPanel.add(lastSyncLabel);
        topRow.add(statsPanel, BorderLayout.EAST);
        panel.add(topRow, BorderLayout.NORTH);

        // Toolbar
        panel.add(createToolbar(), BorderLayout.CENTER);

        // Table
        panel.add(createFileTable(), BorderLayout.SOUTH);

        // Rebuild layout
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG_DARK);
        wrapper.setBorder(new EmptyBorder(10, 12, 6, 12));
        wrapper.add(topRow, BorderLayout.NORTH);
        wrapper.add(createToolbar(), BorderLayout.CENTER);
        JScrollPane tableScroll = createFileTable();
        wrapper.add(tableScroll, BorderLayout.SOUTH);

        // Set table scroll to fill space
        wrapper.setLayout(new BorderLayout());
        wrapper.add(topRow, BorderLayout.NORTH);
        JPanel bodyPanel = new JPanel(new BorderLayout(0, 8));
        bodyPanel.setBackground(BG_DARK);
        bodyPanel.add(createToolbar(), BorderLayout.NORTH);
        bodyPanel.add(createFileTable(), BorderLayout.CENTER);
        wrapper.add(bodyPanel, BorderLayout.CENTER);

        return wrapper;
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(10, 0));
        toolbar.setBackground(BG_DARK);
        toolbar.setBorder(new EmptyBorder(0, 0, 6, 0));

        // Left: action buttons
        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnGroup.setBackground(BG_DARK);

        JButton uploadBtn = createToolbarButton("⬆️  Upload File", ACCENT);
        JButton downloadBtn = createToolbarButton("⬇️  Download", new Color(72, 152, 210));
        JButton deleteBtn = createToolbarButton("🗑️  Xóa", ERROR_COLOR);
        JButton renameBtn = createToolbarButton("✏️  Đổi tên", WARNING);
        JButton historyBtn = createToolbarButton("📜  Lịch sử", ACCENT2);
        JButton refreshBtn = createToolbarButton("🔄  Làm mới", TEXT_SUB);

        uploadBtn.addActionListener(e -> uploadFileManually());
        downloadBtn.addActionListener(e -> downloadSelectedFile());
        deleteBtn.addActionListener(e -> deleteSelectedFile());
        renameBtn.addActionListener(e -> renameSelectedFile());
        historyBtn.addActionListener(e -> showVersionHistory());
        refreshBtn.addActionListener(e -> requestFileList());

        btnGroup.add(uploadBtn);
        btnGroup.add(downloadBtn);
        btnGroup.add(deleteBtn);
        btnGroup.add(renameBtn);
        btnGroup.add(historyBtn);
        btnGroup.add(refreshBtn);
        toolbar.add(btnGroup, BorderLayout.WEST);

        // Right: search
        JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
        searchPanel.setBackground(BG_DARK);
        JLabel searchIcon = new JLabel("🔍");
        searchField = createTextField("Tìm kiếm file...");
        searchField.setForeground(TEXT_SUB);
        searchField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (searchField.getText().equals("Tìm kiếm file...")) {
                    searchField.setText(""); searchField.setForeground(TEXT_MAIN);
                }
            }
            @Override public void focusLost(FocusEvent e) {
                if (searchField.getText().isEmpty()) {
                    searchField.setText("Tìm kiếm file..."); searchField.setForeground(TEXT_SUB);
                }
            }
        });
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
        });
        searchPanel.add(searchIcon, BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);
        toolbar.add(searchPanel, BorderLayout.EAST);

        return toolbar;
    }

    private JScrollPane createFileTable() {
        String[] cols = {"Trạng Thái", "Tên File", "Kích Thước", "Phiên Bản", "Chủ Sở Hữu", "Cập Nhật Lần Cuối", "SHA-256 (ngắn)"};
        fileTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        fileTable = new JTable(fileTableModel);
        fileTable.setBackground(BG_TABLE);
        fileTable.setForeground(TEXT_MAIN);
        fileTable.setGridColor(new Color(40, 50, 70));
        fileTable.setRowHeight(32);
        fileTable.setFont(FONT_BODY);
        fileTable.setSelectionBackground(new Color(60, 90, 140));
        fileTable.setSelectionForeground(Color.WHITE);
        fileTable.setShowHorizontalLines(true);
        fileTable.setShowVerticalLines(false);
        fileTable.setIntercellSpacing(new Dimension(0, 1));
        fileTable.getTableHeader().setBackground(BG_PANEL);
        fileTable.getTableHeader().setForeground(TEXT_SUB);
        fileTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        fileTable.getTableHeader().setReorderingAllowed(false);

        // Column widths
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(70);
        fileTable.getColumnModel().getColumn(4).setPreferredWidth(100);
        fileTable.getColumnModel().getColumn(5).setPreferredWidth(140);
        fileTable.getColumnModel().getColumn(6).setPreferredWidth(110);

        // Custom renderer cho cột trạng thái
        fileTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                setBackground(sel ? new Color(60, 90, 140) : BG_TABLE);
                String val = v != null ? v.toString() : "";
                if (val.contains("SYNCED") || val.contains("✅")) { setForeground(SUCCESS); }
                else if (val.contains("SYNCING") || val.contains("⟳")) { setForeground(WARNING); }
                else if (val.contains("ERROR") || val.contains("❌")) { setForeground(ERROR_COLOR); }
                else if (val.contains("DELETED") || val.contains("🗑️")) { setForeground(TEXT_SUB); }
                else { setForeground(TEXT_MAIN); }
                setHorizontalAlignment(CENTER);
                return this;
            }
        });

        // Sorter
        tableSorter = new TableRowSorter<>(fileTableModel);
        fileTable.setRowSorter(tableSorter);

        // Double-click → download
        fileTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) downloadSelectedFile();
            }
        });

        // Right-click menu
        JPopupMenu popup = new JPopupMenu();
        popup.setBackground(BG_CARD);
        JMenuItem downloadItem = new JMenuItem("⬇️  Tải về");
        downloadItem.addActionListener(e -> downloadSelectedFile());
        JMenuItem deleteItem = new JMenuItem("🗑️  Xóa");
        deleteItem.addActionListener(e -> deleteSelectedFile());
        JMenuItem renameItem = new JMenuItem("✏️  Đổi tên");
        renameItem.addActionListener(e -> renameSelectedFile());
        JMenuItem historyItem = new JMenuItem("📜  Xem lịch sử phiên bản");
        historyItem.addActionListener(e -> showVersionHistory());
        popup.add(downloadItem); popup.add(deleteItem);
        popup.add(renameItem); popup.add(historyItem);
        fileTable.setComponentPopupMenu(popup);

        // Drag-and-drop upload
        new DropTarget(fileTable, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent event) {
                try {
                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = event.getTransferable();
                    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                        for (File f : files) {
                            if (!f.isDirectory()) copyAndUpload(f);
                        }
                    }
                } catch (Exception ex) {
                    LOGGER.warning("Drag-and-drop lỗi: " + ex.getMessage());
                }
            }
        });

        JScrollPane scroll = new JScrollPane(fileTable);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_COL));
        scroll.getViewport().setBackground(BG_TABLE);
        scroll.setPreferredSize(new Dimension(0, 280));
        return scroll;
    }

    // ===== LOG PANEL =====
    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COL),
            new EmptyBorder(8, 12, 8, 12)
        ));

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setBackground(BG_PANEL);
        titleRow.setBorder(new EmptyBorder(0, 0, 6, 0));
        JLabel title = new JLabel("📋  Nhật Ký Hoạt Động");
        title.setFont(FONT_HEADER);
        title.setForeground(TEXT_MAIN);
        JButton clearBtn = createSmallButton("Xóa", TEXT_SUB);
        clearBtn.addActionListener(e -> logArea.setText(""));
        titleRow.add(title, BorderLayout.WEST);
        titleRow.add(clearBtn, BorderLayout.EAST);
        panel.add(titleRow, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setBackground(new Color(10, 12, 20));
        logArea.setForeground(new Color(134, 239, 172));
        logArea.setFont(FONT_MONO);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBorder(new EmptyBorder(5, 8, 5, 8));

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(35, 45, 65)));
        panel.add(scroll, BorderLayout.CENTER);

        // Progress bar
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Sẵn sàng");
        progressBar.setBackground(BG_CARD);
        progressBar.setForeground(ACCENT);
        progressBar.setBorder(new EmptyBorder(4, 0, 0, 0));
        panel.add(progressBar, BorderLayout.SOUTH);

        return panel;
    }

    // ===== STATUS BAR =====
    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        bar.setBackground(new Color(12, 14, 22));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COL));

        statusLabel = new JLabel("⚫  Chưa kết nối");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusLabel.setForeground(TEXT_SUB);
        bar.add(statusLabel);

        JLabel helpLabel = new JLabel(" | Double-click để tải file | Kéo-thả file để upload");
        helpLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        helpLabel.setForeground(new Color(100, 120, 150));
        bar.add(helpLabel);

        return bar;
    }

    // ===================================================
    //  THAO TÁC FILE QUA GUI
    // ===================================================

    /** Chọn file từ máy và upload lên server */
    private void uploadFileManually() {
        if (!syncClient.isConnected()) {
            showError("Chưa kết nối tới server!"); return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File selected : chooser.getSelectedFiles()) {
                copyAndUpload(selected);
            }
        }
    }

    /** Copy file vào thư mục sync và trigger FileWatcher */
    private void copyAndUpload(File sourceFile) {
        new Thread(() -> {
            try {
                File dest = new File(syncDirPath, sourceFile.getName());
                // Nếu file đã trong sync dir thì không copy
                if (!sourceFile.getAbsolutePath().equals(dest.getAbsolutePath())) {
                    java.nio.file.Files.copy(sourceFile.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                appendLog("📋 Đã sao chép vào thư mục sync: " + sourceFile.getName());
                // FileWatcher sẽ tự phát hiện và upload
            } catch (Exception e) {
                appendLog("❌ Lỗi sao chép file: " + e.getMessage());
            }
        }).start();
    }

    /** Tải file được chọn về thư mục sync */
    private void downloadSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row < 0) { showError("Chọn một file để tải về!"); return; }
        int modelRow = fileTable.convertRowIndexToModel(row);
        String filename = (String) fileTableModel.getValueAt(modelRow, 1);
        if (!syncClient.isConnected()) { showError("Chưa kết nối!"); return; }
        syncClient.requestFile(filename, 0);
        appendLog("⬇️  Đang yêu cầu tải: " + filename);
    }

    /** Xóa file được chọn */
    private void deleteSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row < 0) { showError("Chọn một file để xóa!"); return; }
        int modelRow = fileTable.convertRowIndexToModel(row);
        String filename = (String) fileTableModel.getValueAt(modelRow, 1);

        int confirm = JOptionPane.showConfirmDialog(this,
            "Xóa file \"" + filename + "\" khỏi tất cả máy trong mạng?",
            "Xác nhận xóa", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        // Xóa file local
        File file = new File(syncDirPath, filename);
        if (file.exists()) {
            fileWatcher.ignoreFile(filename);
            file.delete();
        }
        // Thông báo server
        syncClient.sendFileDeleted(filename);
        appendLog("🗑️  Đã xóa: " + filename);
    }

    /** Đổi tên file */
    private void renameSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row < 0) { showError("Chọn một file để đổi tên!"); return; }
        int modelRow = fileTable.convertRowIndexToModel(row);
        String oldName = (String) fileTableModel.getValueAt(modelRow, 1);

        String newName = JOptionPane.showInputDialog(this, "Tên mới:", oldName);
        if (newName == null || newName.trim().isEmpty() || newName.equals(oldName)) return;

        File oldFile = new File(syncDirPath, oldName);
        File newFile = new File(syncDirPath, newName.trim());

        if (oldFile.exists()) {
            fileWatcher.ignoreFile(oldName);
            fileWatcher.ignoreFile(newName.trim());
            oldFile.renameTo(newFile);
        }
        syncClient.sendFileRenamed(oldName, newName.trim());
        appendLog("✏️  Đổi tên: " + oldName + " → " + newName.trim());
    }

    /** Hiển thị lịch sử phiên bản */
    private void showVersionHistory() {
        int row = fileTable.getSelectedRow();
        if (row < 0) { showError("Chọn một file!"); return; }
        int modelRow = fileTable.convertRowIndexToModel(row);
        String filename = (String) fileTableModel.getValueAt(modelRow, 1);
        syncClient.requestVersionHistory(filename);
        appendLog("📜 Đang lấy lịch sử: " + filename);

        // Hiện dialog chờ response (demo)
        JOptionPane.showMessageDialog(this,
            "Đã gửi yêu cầu xem lịch sử phiên bản của \"" + filename + "\".\n" +
            "Kết quả sẽ hiện trong log khi server phản hồi.",
            "Lịch sử phiên bản", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Yêu cầu cập nhật danh sách file */
    private void requestFileList() {
        if (syncClient != null && syncClient.isConnected()) {
            syncClient.send(new Message(MessageType.FILE_LIST_REQUEST));
            appendLog("🔄 Đã yêu cầu cập nhật danh sách file.");
        }
    }

    // ===================================================
    //  CẬP NHẬT UI
    // ===================================================

    private void updateFileTable(List<FileMetadata> files) {
        fileTableModel.setRowCount(0);
        long totalSize = 0;
        for (FileMetadata f : files) {
            SyncEngine.SyncStatus status = fileStatusMap.getOrDefault(f.getFilename(), SyncEngine.SyncStatus.SYNCED);
            String statusStr = statusToEmoji(status);
            String hash = f.getSha256() != null
                ? f.getSha256().substring(0, Math.min(12, f.getSha256().length())) + "..."
                : "-";
            fileTableModel.addRow(new Object[]{
                statusStr,
                f.getFilename(),
                f.getReadableSize(),
                "v" + f.getVersion(),
                f.getOwnerClient() != null ? f.getOwnerClient() : "-",
                DATE_FMT.format(new Date(f.getLastModified())),
                hash
            });
            totalSize += f.getFileSize();
        }
        totalFilesLabel.setText(files.size() + " file" + (files.size() != 1 ? "s" : ""));
        totalSizeLabel.setText(formatSize(totalSize));
        lastSyncLabel.setText("Cập nhật: " + TIME_FMT.format(new Date()));
    }

    private void updateFileTableStatus(String filename, SyncEngine.SyncStatus status) {
        for (int i = 0; i < fileTableModel.getRowCount(); i++) {
            if (filename.equals(fileTableModel.getValueAt(i, 1))) {
                fileTableModel.setValueAt(statusToEmoji(status), i, 0);
                break;
            }
        }
    }

    private String statusToEmoji(SyncEngine.SyncStatus status) {
        return switch (status) {
            case SYNCED  -> "✅  Đã sync";
            case SYNCING -> "⟳  Đang sync";
            case ERROR   -> "❌  Lỗi";
            case DELETED -> "🗑️  Đã xóa";
        };
    }

    private void updateConnectionStatus(boolean connected) {
        if (connected) {
            connLabel.setText("🟢 Đã kết nối");
            connLabel.setForeground(SUCCESS);
            statusLabel.setText("🟢  Đã kết nối → " + serverHost + ":" + serverPort);
            statusLabel.setForeground(SUCCESS);
        } else {
            connLabel.setText("🔴 Mất kết nối");
            connLabel.setForeground(ERROR_COLOR);
            statusLabel.setText("🔴  Mất kết nối. Đang thử kết nối lại...");
            statusLabel.setForeground(ERROR_COLOR);
        }
    }

    private void filterTable() {
        String text = searchField.getText().trim();
        if (text.isEmpty() || text.equals("Tìm kiếm file...")) {
            tableSorter.setRowFilter(null);
        } else {
            try {
                tableSorter.setRowFilter(RowFilter.regexFilter("(?i)" + text, 1));
            } catch (Exception ignored) {}
        }
    }

    // ===================================================
    //  TIỆN ÍCH
    // ===================================================

    private void openSyncDirectory() {
        try {
            Desktop.getDesktop().open(new File(syncDirPath));
        } catch (Exception e) {
            appendLog("❌ Không thể mở thư mục: " + e.getMessage());
        }
    }

    private void disconnect() {
        if (syncClient != null) syncClient.disconnect();
        if (syncEngine != null) syncEngine.shutdown();
        updateConnectionStatus(false);
        appendLog("🔌 Đã ngắt kết nối.");
    }

    public void appendLog(String msg) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendLog(msg));
            return;
        }
        String time = TIME_FMT.format(new Date());
        logArea.append("[" + time + "] " + msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Lỗi", JOptionPane.WARNING_MESSAGE);
    }

    private void confirmAndExit() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Bạn có muốn thoát ứng dụng không?",
            "Xác nhận thoát", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) {
            disconnect();
            dispose();
            System.exit(0);
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        else if (bytes < 1024*1024) return String.format("%.1f KB", bytes/1024.0);
        else return String.format("%.1f MB", bytes/(1024.0*1024));
    }

    // ===== UI HELPERS =====

    private JLabel createLabel(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_BODY);
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JLabel createStatLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        label.setForeground(TEXT_SUB);
        return label;
    }

    private JTextField createTextField(String placeholder) {
        JTextField field = new JTextField(placeholder);
        styleTextField(field);
        return field;
    }

    private void styleTextField(JTextField field) {
        field.setBackground(BG_CARD);
        field.setForeground(TEXT_MAIN);
        field.setCaretColor(ACCENT);
        field.setFont(FONT_BODY);
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_COL),
            new EmptyBorder(6, 10, 6, 10)
        ));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private JButton createButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btn.setBorder(new EmptyBorder(10, 22, 10, 22));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setOpaque(true);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        return btn;
    }

    private JButton createSmallButton(String text, Color fg) {
        JButton btn = new JButton(text);
        btn.setBackground(BG_CARD);
        btn.setForeground(fg);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btn.setBorder(new EmptyBorder(5, 12, 5, 12));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JButton createToolbarButton(String text, Color fg) {
        JButton btn = new JButton(text);
        btn.setBackground(BG_CARD);
        btn.setForeground(fg);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_COL),
            new EmptyBorder(5, 12, 5, 12)
        ));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }
}
