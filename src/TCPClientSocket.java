import java.util.ArrayList;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class TCPClientSocket {

    private InputStream in;
    private OutputStream out;
    private DatagramSocket socket;
    private long sequenceNumber;

    private InetSocketAddress destAddr;
    private InetSocketAddress router;
    private int serverPort;

     // TCPClientSocket constructor used by the client to create a client-side connection
     // to a specific server.
     //
    public TCPClientSocket(InetSocketAddress destAddr, InetSocketAddress router) throws IOException {
        this.destAddr = destAddr;
        this.router = router;

        // Bind to a port and connect to destination address
        setupSocket();

        // Initialize Sequence Number
        this.sequenceNumber = (long) (Math.random() * ((32767 - 0) + 1));;

        // Initiate 3-way handshake with server
        connectToServer();

        // Allocate Resources
        allocateResources();
    }

    // TCPClientSocket constructor used by the TCPSocket class to create a server-side connection
    // to a specific client.
    protected TCPClientSocket(InetSocketAddress destAddr, InetSocketAddress router, int serverPort) throws IOException {
        this.destAddr = destAddr;
        this.router = router;
        this.serverPort = serverPort;
        this.socket = new DatagramSocket();

        // Initialize Sequence Number
        this.sequenceNumber = (long) (Math.random() * ((32767 - 0) + 1));

        //Allocate resources
        allocateResources();
    }

    public long getSequenceNumber() {
        return this.sequenceNumber;
    }

    public Packet read() throws IOException {
        // Wait for response from server
        // All packets received are 1024 bytes in size
        byte[] buf = new byte[Packet.PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        // Wait for client to send packet
        this.socket.receive(packet);

        return Packet.fromBuffer(packet.getData());
    }

    public void write(Packet packet) throws IOException {
        send(packet.toDatagramPacket(this.router));
        this.sequenceNumber++;
    }

    public void write(String data) throws IOException {
        ArrayList<DatagramPacket> packets = createDatagramPackets(data.getBytes(StandardCharsets.UTF_8));

        for (DatagramPacket p : packets) {
            send(p);
        }
    }

    private void send(DatagramPacket packet) throws IOException {
        //TODO Implement Timer etc... here
        this.socket.send(packet);
    }

    public InputStream getInputStream() {
        return in;
    }

    public OutputStream getOutputStream() {
        return out;
    }

    public void close() {

    }

    /**
     * Binds the channel to either a random port or a specified source port,
     * then connects it to the destination address and port number.
     * @throws IOException
     */
    private void setupSocket() throws IOException {
        int max = 65535;
        int min = 1024;
        int port = 0;

        // If source port is not set, find a random available port
        if (this.serverPort == 0)
        {
            while(true) {
                port = (int) (Math.random() * ((max - min) + 1)) + min;

                try {
                    this.socket = new DatagramSocket(new InetSocketAddress(port));
                    System.out.println("Succesfully bound socket to port " + port + ".");
                    break;
                } catch (SocketException e) {
                    System.out.println("Port " + port + " is already being used. Trying again ... ");
                    continue;
                }
            }
        }
    }

    /**
     * Performs the client-side 3-way TCP handshake with server
     */
    private void connectToServer() throws IOException {
        // -------------------------
        // Step 1. Send SYN
        // -------------------------

        // Create SYN packet
        Packet SYNPacket = new Packet(Packet.SYN, this.sequenceNumber, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);

        // Send SYN packet to server
        write(SYNPacket);

        // -------------------------
        // Step 2. Wait for SYNACK
        // -------------------------

        // Get SYNACK packet
        Packet SYNACKPacket = read();

        if (SYNACKPacket.getType() != Packet.SYNACK) {
            System.out.println("ERROR: Expected type " + Packet.SYNACK + ". Got " + SYNACKPacket.getType() + " instead. ");
            // SEND FAILURE
            return;
        }

        // -------------------------
        // Step 3. Send ACK
        // -------------------------

        // Create ACK packet
        Packet ACKPacket = new Packet(Packet.ACK, this.sequenceNumber, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);

        // Send ACK to server
        write(ACKPacket);

        System.out.println("Successfully connected to server\n");
    }

    private void allocateResources() {

    }

    private ArrayList<DatagramPacket> createDatagramPackets(byte[] data) {
        ArrayList<DatagramPacket> packets = new ArrayList<>();

        int i = 0;
        while(i < data.length) {

            // Find current payload size
            int payloadLength = data.length - i;

            // Break payload into chunks of 1013 bytes or less
            if (payloadLength > Packet.PAYLOAD_SIZE)
                payloadLength = Packet.PAYLOAD_SIZE;

            // Create a byte buffer of 1024 bytes or less
            ByteBuffer byteBuffer = ByteBuffer.allocate(Packet.HEADER_SIZE + payloadLength);

            // Add header to buffer
            byteBuffer.put((byte) Packet.DATA);
            byteBuffer.putLong(this.sequenceNumber);
            byteBuffer.put(this.destAddr.getAddress().getAddress());
            byteBuffer.putShort((short) this.destAddr.getPort());

            // Create payload byte array
            byte[] payload = new byte[payloadLength];

            for (int j = 0; j < payloadLength; j++) {
                payload[j] = data[i];
                i++;
            }

            byteBuffer.put(payload);

            // Create DatagramPacket and add it to the array list
            byte[] buffer = byteBuffer.array();

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, this.router.getAddress(), this.router.getPort());
            packets.add(packet);

            // Increment sequence number
            this.sequenceNumber++;
        }

        return packets;
    }
}