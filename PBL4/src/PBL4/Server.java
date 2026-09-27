package PBL4;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Vector;

public class Server {
    public static Vector clients = new Vector<>();

    public static void main(String[] args) {
        try {
            ServerSocket server = new ServerSocket(9000);
            System.out.println("Server dang chay tai cong 9000...");
            while (true) {
                Socket soc = server.accept();
                XulyClient x = new XulyClient(soc);
                clients.add(x);
                x.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

class XulyClient extends Thread {
    Socket soc;
    DataInputStream dis;
    DataOutputStream dos;
    public XulyClient(Socket soc) {
        try {
            this.soc = soc;
            dis = new DataInputStream(soc.getInputStream());
            dos = new DataOutputStream(soc.getOutputStream());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void run() {
        try {
            while (true) {
                String msg = dis.readUTF();
                System.out.println("Nhan: " + msg);
                for (int i = 0; i < Server.clients.size(); i++) {
                    XulyClient c = (XulyClient) Server.clients.get(i);
                    c.dos.writeUTF(msg);
                }
            }
        } catch (Exception e) {
            Server.clients.remove(this);
            System.out.println("Mot client da thoat!");
        }
    }
}