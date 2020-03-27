import java.util.ArrayList;
import java.util.Iterator;
import java.util.ArrayDeque;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import java.io.IOException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class TCPSocket {

    private final int WINDOW_SIZE = 10;
    private final static Object lock = new Object();
    private AtomicBoolean running = new AtomicBoolean(true);

    private DatagramSocket socket;
    private int sequenceNumber;
    private int serverPort;
    private InetSocketAddress destAddr;
    private InetSocketAddress peerAddr;
    private InetSocketAddress router;
    private boolean verbose;

    private Listener listener;
    private Sender sender;
    private Receiver receiver;

    private ArrayDeque<Packet> ackWaitQueue;
    private PriorityBlockingQueue<Packet> readQueue;

    // CONSTRUCTORS

    // TCPSocket constructor used by the client to create a client-side connection
    // to a specific server.
    public TCPSocket(InetSocketAddress destAddr, InetSocketAddress router, boolean verbose) throws IOException {
        this.destAddr = destAddr;
        this.router = router;
        this.verbose = verbose;
        this.sequenceNumber = 1;

        // Bind to a port and connect to destination address
        setupSocket();

        // Create ackWaitQueue and readQueue and start Sender, Receiver and Listener threads
        allocateResources();

        // Initiate 3-way handshake with server
        connectToServer();
    }

    // TCPSocket constructor used by the TCPSocket class to create a server-side connection
    // to a specific client.
    protected TCPSocket(InetSocketAddress destAddr, InetSocketAddress router, boolean verbose, int serverPort) throws IOException {
        this.destAddr = destAddr;
        this.router = router;
        this.verbose = verbose;
        this.serverPort = serverPort;
        this.sequenceNumber = 1;
        this.socket = new DatagramSocket();

        // Create ackWaitQueue and readQueue and start Sender, Receiver and Listener threads
        allocateResources();
    }

    // PUBLIC METHODS

    public synchronized void setRouter(InetSocketAddress router) {
        this.router = router;
    }

    // Blocking
    public String read() throws IOException {
        StringBuilder data = new StringBuilder();

        // Block until readQueue contains data
        while (readQueue.isEmpty());

        // Read data from queue
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
        log("TCPSocket.close()", "Closing...");
        try {

            this.listener.close();

            log("TCPSocket.close()", "Shutting down socket ...");

            this.running.set(false);
            this.listener.join();
            this.sender.join();
            this.receiver.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // SERVER-SIDE accessible methods

    // protected void write(Packet packet) throws IOException {
    //     this.sender.put(packet);
    //     this.sequenceNumber++;
    // }

    protected int getSequenceNumber() {
        return this.sequenceNumber;
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

    // PRIVATE METHODS

    private void log(String method, String str) {
        if (this.verbose) {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd:MM:yyy HH:mm:ss");
            LocalDateTime now = LocalDateTime.now();
            System.out.println("[" + dtf.format(now) + " " + method + "] " + str);
        }
    }

    private synchronized void setPeerAddress(InetSocketAddress addr) {
        this.peerAddr = addr;
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
                    log("TCPSocket.setupSocket()", "Succesfully bound socket to port " + port + ".");
                    break;
                } catch (SocketException e) {
                    log("TCPSocket.setupSocket()", "Port " + port + " is already being used. Trying again ... ");
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
            log("TCPSocket.connectToServer()", "[3-WAY] Sending SYN ...");
            Packet SYNPacket = new Packet(Packet.SYN, this.sequenceNumber, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);

            // Send SYN to server and pass packet to Sender thread
            this.sender.put(SYNPacket);

            // Increment sequence number
            this.sequenceNumber++;

            // Step 2. Wait for Listener thread to receive SYNACK
            synchronized(lock) {
                lock.wait();
            }

            log("TCPSocket.connectToServer()", "[3-WAY] Received SYNACK from server ...");

            // Step 3. Send ACK
            Packet ACKPacket = new Packet(Packet.ACK, 1, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);

            // Send ACK directly to server, bypassing the Sender thread.
            this.socket.send(ACKPacket.toDatagramPacket(router));

            log("TCPSocket.connectToServer()", "[3-WAY] Sending ACK ...");
            log("TCPSocket.connectToServer()", "Successfully connected to server");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void allocateResources() {
        this.ackWaitQueue = new ArrayDeque<>();
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

    // Threads

    /**
     *
     */
    private class Sender extends Thread {

        private PriorityBlockingQueue<Packet> sendBuffer;
        private int nextSeqNum;
        private int base;

        public Sender() {
            this.base = 1;
            this.nextSeqNum = 1;
            this.sendBuffer = new PriorityBlockingQueue<>(WINDOW_SIZE, new Packet.PacketComparator());
        }

        @Override
        public void run() {
            try {
                while(running.get()) {
                    if (!sendBuffer.isEmpty() && nextSeqNum == sendBuffer.peek().getSequenceNumber()) {
                        Packet packet = sendBuffer.poll();

                        log("TCPSocket.Sender.run()", "Sending packet: " + packet);

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

            log("TCPSocket.Sender.run()", "Closing Sender");
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

                    log("TCPSocket.Sender.processACK()", "Successfully received ACK for packet " + packet.getSequenceNumber());
                    if (packet.getSequenceNumber() == base) {
                        base++;
                    }

                    break;
                    //TODO TIMER STUFF??
                }
            }
        }

        private void processNAK(Packet packet) {
            log("TCPSocket.Sender.processNAK()", "Processing " + packet);
        }
    }

    /**
     *
     */
    private class Receiver extends Thread {
        private PriorityBlockingQueue<Packet> receiveBuffer;
        private int base;

        public Receiver() {
            this.base = 2;
            this.receiveBuffer = new PriorityBlockingQueue<>(WINDOW_SIZE, new Packet.PacketComparator());
        }

        @Override
        public void run() {
            while(running.get()) {
                if (!receiveBuffer.isEmpty() && receiveBuffer.peek().getSequenceNumber() == base) {
                    Packet packet = receiveBuffer.poll();
                    readQueue.add(packet);
                    base++;
                }
            }
            log("TCPSocket.Receiver.run()", "Closing Receiver");
        }

        public void put(Packet packet) throws IOException {
            if (packet.getSequenceNumber() < (base + WINDOW_SIZE)) {
                log("TCPSocket.Receiver.put()", "Received packet: " + packet);
                // Send ACK to all packets within the window and any packet previously received
                sendACK(packet);

                if (packet.getSequenceNumber() >= base) {
                    // Add packet to receive buffer
                    receiveBuffer.put(packet);
                }
            }
        }

        private void sendACK(Packet packet) throws IOException {
            log("TCPSocket.Receiver.sendACK()", "Sending ACK for " + packet);
            Packet ack = new Packet(Packet.ACK, packet.getSequenceNumber(), destAddr.getAddress(), destAddr.getPort(), new byte[0]);
            socket.send(ack.toDatagramPacket(router));
        }

        private void sendNAK(Packet packet) throws IOException {
            log("TCPSocket.Receiver.sendNAK()", "Sending NAK for " + packet);
            Packet nak = new Packet(Packet.NAK, packet.getSequenceNumber(), destAddr.getAddress(), destAddr.getPort(), new byte[0]);
            socket.send(nak.toDatagramPacket(router));
        }
    }

    private class Listener extends Thread {

        @Override
        public void run() {
            while(running.get()) {
                try {
                    Packet packet = listenForPacket();

                    // Shut down signal was caught in listenForPacket()
                    // and it returned null
                    if (packet == null)
                        break;

                    switch(packet.getType()) {
                        case Packet.SYNACK:
                            synchronized(lock) {
                                setPeerAddress(new InetSocketAddress(packet.getPeerAddress(), packet.getPeerPort()));
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
                }
            }
            log("TCPSocket.Listener.run()", "Closing Listener");
        }

        private Packet listenForPacket() throws IOException {
            byte[] buf = new byte[Packet.PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            // Set a receive timeout to prevent indefinite blocking
            socket.setSoTimeout(500);

            while (running.get()) {
                try {
                    // Wait for client to send datagram packet
                    socket.receive(packet);

                    // Create Packet from buffer
                    return Packet.fromBuffer(buf);
                } catch (SocketTimeoutException e) {
                    continue;
                }
            }

            return null;
        }

        private void processFIN(Packet packet) {

        }

        public void close() {

            pause();

            // Send FIN to server
            log("TCPSocket.Listener.close()", "Sending FIN to " + this.peerAddr.getAddress() + ":" + this.peerAddr.getPort());
            Packet FINPacket = new Packet(Packet.FIN, this.sequenceNumber, this.peerAddr.getAddress(), this.peerAddr.getPort(), new byte[0]);
            this.socket.send(FINPacket.toDatagramPacket(router));
            this.sequenceNumber++;

            // Wait for ACK
            listener.pause();

            log("TCPSocket.Listener.close()", "Waiting for ACK ...");

            while(true) {
                byte[] buf = new byte[1024];
                DatagramPacket dgPacket = new DatagramPacket(buf, buf.length);

                // Listen for ACK
                this.socket.receive(padgPacketcket);
                Packet packet = Packet.fromBuffer(buf);

                if (packet.getType() == Packet.ACK && packet.getSequenceNumber() == FINPacket.getSequenceNumber()) {
                    log("TCPSocket.Listener.close()", "Received ACK.");
                    break;
                }
                else
                    this.receiver.put(packet);
            }

            log("TCPSocket.Listener.close()", "Waiting for FIN ...");

            while(true) {
                byte[] buf = new byte[1024];
                DatagramPacket dgPacket = new DatagramPacket(buf, buf.length);

                // Listen for FIN
                this.socket.receive(padgPacketcket);
                Packet packet = Packet.fromBuffer(buf);

                if (packet.getType() == Packet.FIN){
                    log("TCPSocket.Listener.close()", "Received FIN.");
                    log("TCPSocket.Listener.close()", "Sending ACK ...");
                    this.receiver.sendACK(packet);
                    break;
                }
                else
                    this.receiver.put(packet);
             }

        }
    }
}