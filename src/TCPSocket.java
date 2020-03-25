import java.util.HashMap;
import java.util.Set;

import javafx.scene.chart.PieChart.Data;

import java.io.IOException;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;

public class TCPSocket {

    private int port;
    private DatagramChannel serverChannel;
    private SelectionKey selectionKey;
    private SocketAddress router;
    private Selector selector;
    private HashMap<InetSocketAddress, DatagramChannel> clients;

    public TCPSocket(int port) throws IOException {
        this.port = port;

        // Initialize listening channel
        this.serverChannel = DatagramChannel.open();
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.socket().bind(new InetSocketAddress(this.port));
        serverChannel.configureBlocking(false);

        // Register listening channel with the selector
        this.selector = Selector.open();
        this.selectionKey = this.serverChannel.register(this.selector, SelectionKey.OP_READ);

        // Initialize map of clients
        this.clients = new HashMap<>();
    }

    public TCPClientSocket accept() throws IOException {

        System.out.println("Accepting new connection");

        while(true) {
            Packet packet = listenForPacket();

            if (packet.getType() == Packet.SYN) {
                // Start 3-way handshake with client
                TCPClientSocket clientSocket = connectToClient(packet);

                // Return new client connection
                return clientSocket;
            } else {
                processPacket(packet);
            }
        }
    }

    private Packet listenForPacket() throws IOException {
        // Wait for client to send packet
        this.selector.select();

        // Clear keys
        selector.selectedKeys().clear();

        // All packets are 1024 bytes in size
        ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN).order(ByteOrder.BIG_ENDIAN);
        buf.clear();

        // Get return address to router
        this.router = this.serverChannel.receive(buf);

        // Flip buffer to read from it
        buf.flip();

        // Create Packet from buffer
        return Packet.fromBuffer(buf);
    }

     // Performs the server-side 3-way TCP handshake with client
    private TCPClientSocket connectToClient(Packet SYNPacket) throws IOException {

        System.out.println("Receiving new connection");

        // -------------------------
        // Step 1. Process SYN
        // -------------------------

        // Perform 3-way handshake with incoming client
        InetSocketAddress clientAddr = SYNPacket.getFullAddress();
        DatagramChannel clientChannel = DatagramChannel.open();

        // Add to map
        // TODO verify not already exists
        this.clients.put(clientAddr, clientChannel);

        System.out.println("[3-WAY] Received SYN from " + clientAddr + ". Creating client socket ...");

        // Create client socket
        TCPClientSocket clientSocket = new TCPClientSocket(clientAddr, this.router, clientChannel, this.port);

        System.out.println("[3-WAY] Client socket created. Sending SYNACK ...");

        // -------------------------
        // Step 2. Send SYN-ACK
        // -------------------------

        // Create SYNACK packet
        Packet SYNACKPacket = new Packet(Packet.SYNACK, clientSocket.getSequenceNumber(), clientAddr.getAddress(), clientAddr.getPort(), new byte[0]);

        clientSocket.write(SYNACKPacket);

        // -------------------------
        // Step 3. Listen for ACK
        // -------------------------

        Packet ACKPacket = listenForPacket();

        System.out.println("[3-WAY] Received ACK from client. Client is now connected.");

        if (ACKPacket.getType() != Packet.ACK) {
            System.out.println("ERROR: Expected type " + Packet.ACK + ". Got " + ACKPacket.getType() + " instead. ");
            // SEND FAILURE
            return null;
        }

        return clientSocket;
    }

    private void processPacket(Packet packet) {

    }
}