import java.util.HashMap;
import java.util.Set;

import java.io.IOException;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
// import java.nio.channels.SelectionKey;
// import java.nio.channels.Selector;
// import java.nio.channels.DatagramChannel;
// import java.nio.channels.SelectableChannel;

public class TCPSocket {

    private int port;
    private DatagramSocket socket;
    private InetSocketAddress router;
    private InetSocketAddress requestAddr;
    private HashMap<InetSocketAddress, TCPClientSocket> clients;

    private Listener listener;

    public final static Object lock = new Object();

    public TCPSocket(int port) throws IOException {
        this.port = port;

        // Create listening socket
        this.socket = new DatagramSocket(port);
        System.out.println("\nServer is listening on port " + this.port + "\n");

        // Initialize map of clients
        this.clients = new HashMap<>();

        // Start listening for packets
        this.listener = new Listener();
        this.listener.start();
    }

    public TCPClientSocket accept() throws IOException {
        try {
            System.out.println("Accepting new connection\n");

            // -------------------------
            // Step 1. Wait for SYN
            // -------------------------

            synchronized(lock) {
                lock.wait();
            }

            // Perform 3-way handshake with incoming client
            System.out.println("[3-WAY] Received SYN from " + requestAddr + ". Creating client socket ...");

            TCPClientSocket clientSocket = new TCPClientSocket(requestAddr, router, port);

            // -------------------------
            // Step 2. Send SYN-ACK
            // -------------------------

            // Create SYNACK packet
            System.out.println("[3-WAY] Client socket created. Sending SYNACK ...");

            Packet SYNACKPacket = new Packet(Packet.SYNACK, clientSocket.getSequenceNumber(), requestAddr.getAddress(), requestAddr.getPort(), new byte[0]);

            // Send packet back to client
            clientSocket.write(SYNACKPacket);

            // -------------------------
            // Step 3. Wait for ACK
            // -------------------------

            synchronized(lock) {
                lock.wait();
            }

            System.out.println("[3-WAY] Received ACK from client. Client is now connected.");

            // Add to map
            addClient(requestAddr, clientSocket);

            return clientSocket;

        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    // private class Accepter extends Thread {
    //     @Override
    //     public void run() {
    //         // -------------------------
    //         // Step 1. Wait for SYN
    //         // -------------------------
    //         try {
    //             System.out.println("Accepting new connection\n");
    //             synchronized(lock) {
    //             lock.wait();
    //             }

    //             // Perform 3-way handshake with incoming client
    //             System.out.println("[3-WAY] Received SYN from " + requestAddr + ". Creating client socket ...");

    //             TCPClientSocket clientSocket = new TCPClientSocket(requestAddr, router, port);

    //             // -------------------------
    //             // Step 2. Send SYN-ACK
    //             // -------------------------

    //             // Create SYNACK packet
    //             System.out.println("[3-WAY] Client socket created. Sending SYNACK ...");

    //             Packet SYNACKPacket = new Packet(Packet.SYNACK, clientSocket.getSequenceNumber(), requestAddr.getAddress(), requestAddr.getPort(), new byte[0]);

    //             // Send packet back to client
    //             clientSocket.write(SYNACKPacket);

    //             // -------------------------
    //             // Step 3. Wait for ACK
    //             // -------------------------

    //             synchronized(lock) {
    //                 lock.wait(10);
    //                 }


    //             System.out.println("[3-WAY] Received ACK from client. Client is now connected.");

    //             // Add to map
    //             addClient(requestAddr, clientSocket);

    //         } catch (InterruptedException e) {
    //             e.printStackTrace();
    //         } catch (IOException e) {
    //             e.printStackTrace();
    //         }
    //     }
    // }

    private synchronized void setRequestAddress(InetSocketAddress addr) {
        this.requestAddr = addr;
    }

    private synchronized void setRouter(InetSocketAddress router) {
        this.router = router;
    }

    private synchronized void addClient(InetSocketAddress addr, TCPClientSocket client) {
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
                                    break;
                                }
                            }
                        case Packet.DATA:
                        case Packet.NAK:
                            forward(packet);
                            break;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private Packet listenForPacket() throws IOException {

            System.out.println("Listening for packets");
            // All packets are 1024 bytes in size
            byte[] buf = new byte[Packet.PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            // Wait for client to send packet
            socket.receive(packet);

            // Get return address to router
            setRouter(new InetSocketAddress(packet.getAddress(), packet.getPort()));

            // Create Packet from buffer
            Packet p = Packet.fromBuffer(buf);

            System.out.println(p.toString());
            return p;
        }

        private void forward(Packet packet) throws IOException {
            InetSocketAddress clientAddr = new InetSocketAddress(packet.getPeerAddress(), packet.getPeerPort());

            TCPClientSocket clientSocket = clients.get(clientAddr);
            clientSocket.write(packet);
        }
    }
}