import java.util.HashMap;
import java.util.Set;

import java.io.IOException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TCPServerSocket {

    private final static Object lock = new Object();

    private int port;
    private boolean verbose;
    private DatagramSocket socket;
    private InetSocketAddress router;
    private InetSocketAddress requestAddr;
    private HashMap<InetSocketAddress, TCPSocket> clients;
    private Listener listener;

    public TCPServerSocket(int port) throws IOException {
        this.port = port;

        // Create listening socket
        this.socket = new DatagramSocket(port);
        log("TCPServerSocket.TCPServerSocket()", "\nServer is listening on port " + this.port);

        // Initialize map of clients
        this.clients = new HashMap<>();

        // Start listening for packets
        this.listener = new Listener();
        this.listener.start();
    }

    public TCPSocket accept() throws IOException {
        try {
            log("TCPServerSocket.accept()", "Accepting new connection");

            // Step 1. Wait for SYN
            synchronized(lock) {
                lock.wait();
            }

            log("TCPServerSocket.accept()", "Received SYN from " + requestAddr + ". Creating client socket ...");
            TCPSocket clientSocket = new TCPSocket(this.requestAddr, this.router, this.verbose, this.port);

            // Step 2. Send SYN-ACK
            log("TCPServerSocket.accept()", "Client socket created. Sending SYNACK ...");
            Packet SYNACKPacket = new Packet(Packet.SYNACK, clientSocket.getSequenceNumber(), requestAddr.getAddress(), requestAddr.getPort(), new byte[0]);

            // Send packet back to client
            clientSocket.write(SYNACKPacket);

            // Step 3. Wait for ACK
            synchronized(lock) {
                lock.wait();
            }

            addClient(requestAddr, clientSocket);
            log("TCPServerSocket.accept()", "Received ACK from client. Client is now connected.");

            return clientSocket;

        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    private void log(String method, String str) {
        if (this.verbose) {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd:MM:yyy HH:mm:ss");
            LocalDateTime now = LocalDateTime.now();
            System.out.println("[" + dtf.format(now) + " " + method + "] " + str);
        }
    }

    private synchronized void setRequestAddress(InetSocketAddress addr) {
        this.requestAddr = addr;
    }

    private synchronized void setRouter(InetSocketAddress router) {
        this.router = router;
    }

    private synchronized void addClient(InetSocketAddress addr, TCPSocket client) {
        this.clients.put(addr, client);
        setRequestAddress(null);
    }

    private class Listener extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    Packet packet = listenForPacket();

                    InetSocketAddress clientAddr = new InetSocketAddress(packet.getPeerAddress(), packet.getPeerPort());

                    switch(packet.getType()) {
                        case Packet.SYN:
                            synchronized(lock) {
                                setRequestAddress(clientAddr);
                                lock.notify();
                            }
                            break;
                        case Packet.ACK:
                            synchronized(lock) {
                                if (requestAddr != null && requestAddr.equals(clientAddr)) {
                                    lock.notify();
                                }
                            }
                        case Packet.DATA:
                        case Packet.NAK:
                            // Wait until the client socket has been added to the clients map.
                            while (!clients.containsKey(clientAddr));

                            TCPSocket clientSocket = clients.get(clientAddr);
                            clientSocket.receive(packet);
                            break;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private Packet listenForPacket() throws IOException {
            // All packets are 1024 bytes in size
            byte[] buf = new byte[Packet.PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            // Wait for client to send packet
            socket.receive(packet);

            // Get return address to router
            setRouter(new InetSocketAddress(packet.getAddress(), packet.getPort()));

            // Create Packet from buffer
            return Packet.fromBuffer(buf);
        }
    }
}