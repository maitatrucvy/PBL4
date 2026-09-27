package PBL4;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

public class Client extends JFrame implements Runnable {
    JTextField txtIP = new JTextField("localhost", 10);
    JTextField txtTen = new JTextField("May_1", 10);
    JTextField txtThuMuc = new JTextField("", 15);
    JTextField txtFile = new JTextField("", 10);
    JTextArea txtThongBao = new JTextArea(12, 40);
    JButton btnKetNoi = new JButton("Kết nối");
    JButton btnGui = new JButton("Báo tệp thay đổi");
    Socket soc;
    DataInputStream dis;
    DataOutputStream dos;

    public Client() {
        setTitle("Demo Dong Bo Tep Tin Trong Mang LAN");
        setLayout(new FlowLayout());
        add(new JLabel("IP Server:"));
        add(txtIP);
        add(new JLabel("Tên máy:"));
        add(txtTen);
        add(btnKetNoi);
        add(new JLabel("Thư mục đồng bộ:"));
        add(txtThuMuc);
        add(new JLabel("Tên tệp:"));
        add(txtFile);
        add(btnGui);
        txtThongBao.setEditable(false);
        add(new JScrollPane(txtThongBao));
        btnGui.setEnabled(false);

        btnKetNoi.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                ketNoiServer();
            }
        });

        btnGui.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                guiThongBao();
            }
        });

        setSize(500, 360);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setVisible(true);
    }

    public void ketNoiServer() {
        try {
            soc = new Socket(txtIP.getText(), 9000);
            dis = new DataInputStream(soc.getInputStream());
            dos = new DataOutputStream(soc.getOutputStream());
            btnKetNoi.setEnabled(false);
            btnGui.setEnabled(true);
            txtThongBao.append(">> Đã kết nối thành công tới Server!\n");
            new Thread(this).start();
        } catch (Exception e) {
            txtThongBao.append(">> Không thể kết nối đến Server!\n");
        }
    }

    public void guiThongBao() {
        try {
            String msg = "[" + txtTen.getText() + "] vừa sửa file: " 
                         + txtFile.getText() + " (trong thư mục: " + txtThuMuc.getText() + ")";
            dos.writeUTF(msg);
        } catch (Exception e) {
            txtThongBao.append(">> Lỗi gửi dữ liệu!\n");
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                String msg = dis.readUTF();
                txtThongBao.append(msg + "\n");
            }
        } catch (Exception e) {
            txtThongBao.append(">> Mất kết nối tới Server!\n");
        }
    }

    public static void main(String[] args) {
        new Client();
    }
}