import java.util.ArrayList;
import java.util.Iterator;
import java.util.ArrayDeque;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

public class TCPSocket {

    private final int WINDOW_SIZE = 10;

    public final static Object lock = new Object();

    protected InetSocketAddress router;
    private DatagramSocket socket;
    private int sequenceNumber;
    private InetSocketAddress destAddr;
    private int serverPort;

    private Listener listener;
    private Sender sender;
    private Receiver receiver;

    protected PriorityBlockingQueue<Packet> sendBuffer;
    protected ArrayDeque<Packet> ackWaitQueue;
    protected PriorityBlockingQueue<Packet> receiveBuffer;
    protected PriorityBlockingQueue<Packet> readQueue;

    // TCPSocket constructor used by the client to create a client-side connection
    // to a specific server.
    public TCPSocket(InetSocketAddress destAddr, InetSocketAddress router) throws IOException {
        this.destAddr = destAddr;
        this.router = router;

        // Bind to a port and connect to destination address
        setupSocket();

        // Initialize Sequence Number
        this.sequenceNumber = 1;

        // Allocate Resources
        allocateResources();

        // Initiate 3-way handshake with server
        connectToServer();
    }

    // TCPSocket constructor used by the TCPSocket class to create a server-side connection
    // to a specific client.
    protected TCPSocket(InetSocketAddress destAddr, InetSocketAddress router, int serverPort) throws IOException {
        this.destAddr = destAddr;
        this.router = router;
        this.serverPort = serverPort;
        this.socket = new DatagramSocket();

        // Initialize Sequence Number
        this.sequenceNumber = 1;

        //Allocate resources
        allocateResources();
    }

    protected int getSequenceNumber() {
        return this.sequenceNumber;
    }

    public synchronized void setRouter(InetSocketAddress router) {
        this.router = router;
    }

    public String read() throws IOException {
        StringBuilder data = new StringBuilder();
        while (!readQueue.isEmpty()) {
            Packet packet = readQueue.poll();
            // System.out.println("[Read] Packet" + packet);
            if (packet.getType() == Packet.DATA) {
                for (byte b : packet.getPayload()) {
                    data.append((char) b);
                }
            }
        }

        return data.toString();
    }

    protected void write(Packet packet) throws IOException {
        this.sender.put(packet);
        this.sequenceNumber++;
    }

    public void write(String data) throws IOException {
        write(data.getBytes(StandardCharsets.UTF_8));
    }

    public void write(byte[] data) throws IOException {
        ArrayList<Packet> packets = createPackets(data);

        for (Packet p : packets) {
            this.sender.put(p);
        }
    }

    public void close() {
        try {
            System.out.println("Closing...");
            this.sender.interrupt();
            this.receiver.interrupt();
            this.listener.interrupt();

            this.sender.join();
            this.receiver.join();
            this.listener.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void receive(Packet packet) throws IOException {
        switch(packet.getType()) {
            case Packet.ACK:
                this.sender.processACK(packet);
                break;
            case Packet.NAK:
                this.sender.processNAK(packet);
                break;
            case Packet.DATA:
                this.receiver.put(packet);
                break;
        }
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
        try {
            // Step 1. Send SYN
            System.out.println("[3-WAY] Sending SYN ...");
            Packet SYNPacket = new Packet(Packet.SYN, this.sequenceNumber, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);

            write(SYNPacket);

            // Step 2. Wait for SYNACK
            synchronized(lock) {
                lock.wait();
            }
            System.out.println("[3-WAY] Received SYNACK from server ...");

            // Step 3. Send ACK
            Packet ACKPacket = new Packet(Packet.ACK, 1, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);
            this.socket.send(ACKPacket.toDatagramPacket(router));

            System.out.println("[3-WAY] Sending ACK ...");
            System.out.println("Successfully connected to server\n");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void allocateResources() {
        this.sendBuffer = new PriorityBlockingQueue<>(WINDOW_SIZE, new Packet.PacketComparator());
        this.ackWaitQueue = new ArrayDeque<>();
        this.receiveBuffer = new PriorityBlockingQueue<>(WINDOW_SIZE, new Packet.PacketComparator());
        this.readQueue = new PriorityBlockingQueue<>(1000, new Packet.PacketComparator());

        this.listener = new Listener();
        this.sender = new Sender();
        this.receiver = new Receiver();

        this.listener.start();
        this.sender.start();
        this.receiver.start();
    }

    private ArrayList<Packet> createPackets(byte[] data) throws IOException {
        ArrayList<Packet> packets = new ArrayList<>();

        int i = 0;
        while(i < data.length) {

            // Find current payload size
            int payloadLength = data.length - i;

            // Break payload into chunks of 1013 bytes or less
            if (payloadLength > Packet.PAYLOAD_SIZE)
                payloadLength = Packet.PAYLOAD_SIZE;

            // Create a byte buffer of 1024 bytes or less
            ByteBuffer byteBuffer = ByteBuffer.allocate(Packet.HEADER_SIZE + payloadLength).order(ByteOrder.BIG_ENDIAN);

            // Add header
            byteBuffer.put((byte) Packet.DATA);
            byteBuffer.putInt((int)this.sequenceNumber);
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

            Packet packet = Packet.fromBuffer(buffer);
            packets.add(packet);

            // Increment sequence number
            this.sequenceNumber++;
        }

        return packets;
    }

    /**
     *
     */
    private class Sender extends Thread {

        private int nextSeqNum;
        private int base;

        public Sender() {
            this.base = 1;
            this.nextSeqNum = 1;
        }

        @Override
        public void run() {
            try {
                while(true) {
                    if (!sendBuffer.isEmpty() && nextSeqNum == sendBuffer.peek().getSequenceNumber()) {
                        Packet packet = sendBuffer.poll();

                        // System.out.println("\n[SENDER]" + packet);

                        // Put packet into ack queue
                        ackWaitQueue.add(packet);

                        // Send packet to router
                        socket.send(packet.toDatagramPacket(router));
                        nextSeqNum++;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void put(Packet packet) throws IOException {
            if (packet.getSequenceNumber() < base + WINDOW_SIZE) {
                sendBuffer.add(packet);
            }
        }

        private void processACK(Packet packet) {
            Iterator<Packet> it = ackWaitQueue.iterator();

            while(it.hasNext()) {
                if (packet.getSequenceNumber() == it.next().getSequenceNumber()) {
                    it.remove();

                    if (packet.getSequenceNumber() == base) {
                        base++;
                    }

                    break;
                    //TODO TIMER STUFF??
                }
            }
        }

        private void processNAK(Packet packet) {
            System.out.println("[ProcessNAK] " + packet);
        }
    }

    /**
     *
     */
    private class Receiver extends Thread {
        private int base;

        public Receiver() {
            this.base = 2;
        }

        @Override
        public void run() {
            while(true) {
                if (!receiveBuffer.isEmpty()) {
                    // if (receiveBuffer.peek().getSequenceNumber() == base) {
                    Packet packet = receiveBuffer.poll();
                    // System.out.println("\n[RECEIVER]" + packet);
                    readQueue.add(packet);
                }
            }
        }

        public void put(Packet packet) throws IOException {
            // System.out.println("BASE + WIN = " + (base + WINDOW_SIZE));
            if (packet.getSequenceNumber() < (base + WINDOW_SIZE)) {
                // System.out.println("[Receiver.put()] " + packet);
                sendACK(packet);
                // Add packet to receive buffer
                receiveBuffer.put(packet);
            }
        }

        private void sendACK(Packet packet) throws IOException {
            // System.out.println("[Sending ACK] for " + packet);
            Packet ack = new Packet(Packet.ACK, packet.getSequenceNumber(), destAddr.getAddress(), destAddr.getPort(), new byte[0]);
            socket.send(ack.toDatagramPacket(router));
            base++;
        }

        private void sendNAK(Packet packet) throws IOException {
            Packet nak = new Packet(Packet.NAK, packet.getSequenceNumber(), destAddr.getAddress(), destAddr.getPort(), new byte[0]);
            socket.send(nak.toDatagramPacket(router));
        }
    }

    private class Listener extends Thread {
        @Override
        public void run() {
            while(true) {
                try {
                    Packet packet = listenForPacket();

                    // System.out.println("[LISTENER] " + packet);
                    switch(packet.getType()) {
                        case Packet.SYNACK:
                            synchronized(lock) {
                                lock.notify();
                            }
                        case Packet.ACK:
                            sender.processACK(packet);
                            break;
                        case Packet.NAK:
                            sender.processNAK(packet);
                            break;
                        case Packet.DATA:
                            receiver.put(packet);
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

            // Create Packet from buffer
            return Packet.fromBuffer(buf);
        }
    }
}