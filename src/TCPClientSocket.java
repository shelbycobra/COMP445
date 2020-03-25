import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Set;
import java.nio.channels.DatagramChannel;
import java.nio.channels.AlreadyBoundException;

public class TCPClientSocket {

    private InputStream in;
    private OutputStream out;
    private DatagramChannel channel;
    private long sequenceNumber;

    private Selector readSelector;
    private Selector writeSelector;
    private InetSocketAddress destAddr;
    private SocketAddress router;
    private int srcPort;

     // TCPClientSocket constructor used by the client to create a client-side connection
     // to a specific server.
     //
    public TCPClientSocket(InetSocketAddress destAddr, SocketAddress router) throws IOException {
        this.destAddr = destAddr;
        this.router = router;

        // Create DatagramChannel
        this.channel = DatagramChannel.open();

        // Bind to a port and connect to destination address
        setupChannel();

        // Initialize Sequence Number
        this.sequenceNumber = (long) (Math.random() * ((32767 - 0) + 1));;

        // Initiate 3-way handshake with server
        connectToServer();

        // Allocate Resources
        allocateResources();
    }

    // TCPClientSocket constructor used by the TCPSocket class to create a server-side connection
    // to a specific client.
    protected TCPClientSocket(InetSocketAddress destAddr, SocketAddress router, DatagramChannel channel, int srcPort) throws IOException {
        this.destAddr = destAddr;
        this.router = router;
        this.channel = channel;
        this.srcPort = srcPort;

        // Allows for multiple channels to be bound to the same source port
        this.channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);

        // Bind to a port and connect to destination address
        setupChannel();

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
        this.readSelector.select();

        // Clear keys
        this.readSelector.selectedKeys().clear();

        // Clear buffer content and read from channel
        ByteBuffer packetBuf = ByteBuffer.allocate(Packet.MAX_LEN).order(ByteOrder.BIG_ENDIAN);
        packetBuf.clear(); // To Write

        // Read packet from channel
        this.channel.receive(packetBuf);
        packetBuf.flip(); // To Read

        return Packet.fromBuffer(packetBuf);
    }

    public void write(Packet packet) throws IOException {
        this.channel.send(packet.toBuffer(), this.router);
        this.sequenceNumber++;
    }

    public InputStream getInputStream() {
        ByteBuffer header = ByteBuffer.allocate(Packet.HEADER).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer payload = ByteBuffer.allocate(Packet.PAYLOAD).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer[] buffers = { header, payload };

        header.clear();
        payload.clear();

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
    private void setupChannel() throws IOException {
        int max = 65535;
        int min = 1024;
        int port = 0;

        // If source port is not set, find a random available port
        if (this.srcPort == 0)
        {
            while(true) {
                port = (int) (Math.random() * ((max - min) + 1)) + min;

                try {
                    this.channel.bind(new InetSocketAddress(port));
                    System.out.println("Succesfully bound channel to port " + port + ".");
                    break;
                } catch (AlreadyBoundException e) {
                    System.out.println("Port " + port + " is already being used. Trying again ... ");
                    continue;
                }
            }
        }

        // Bind channel to specified source port
        else
        {
            try {
                this.channel.bind(new InetSocketAddress(this.srcPort));
                System.out.println("Succesfully bound channel to port " + this.srcPort + ".");
            } catch (AlreadyBoundException e) {
                System.out.println("Port " + port + " is already being used. Address reuse was not set.");
            }
        }

        // Initialize selectors
        this.readSelector = Selector.open();
        this.writeSelector = Selector.open();

        // Register channel with read and write selectors
        this.channel.configureBlocking(false);
        this.channel.register(this.readSelector, SelectionKey.OP_READ );
        this.channel.register(this.writeSelector, SelectionKey.OP_WRITE);
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
        Packet ACKPacket = new Packet(Packet.ACK, this.sequenceNumber, this.destAddr.getAddress(), this.destAddr.getPort(), new String("HELLO~").getBytes());

        // Send ACK to server
        write(ACKPacket);
    }

    private void allocateResources() {

    }
}