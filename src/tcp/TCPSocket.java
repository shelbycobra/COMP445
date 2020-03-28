package tcp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class TCPSocket {

    private final int WINDOW_SIZE = 10;
    private final static Object connectLock = new Object();
    private final static Object incrementLock = new Object();

    private int sequenceNumber;
    private int serverPort;
    private boolean verbose;
    private InetSocketAddress destAddr;
    private InetSocketAddress router;
    private DatagramSocket socket;

    private Semaphore waitingForFINACK = new Semaphore(1);
    private Semaphore waitingForFIN = new Semaphore(1);

    private Processor processor;
    private Listener listener;
    private Sender sender;
    private Receiver receiver;

    // CONSTRUCTORS

    // TCPSocket constructor used by the client to create a client-side connection
    // to a specific server.
    public TCPSocket(InetSocketAddress destAddr, InetSocketAddress router, boolean verbose) throws IOException {
        this(destAddr, router, verbose, true);
    }

    // TCPSocket constructor used by the TCPSocket class to create a server-side connection
    // to a specific client.
    protected TCPSocket(InetSocketAddress destAddr, InetSocketAddress router, boolean verbose, boolean connectToServer) throws IOException {
        this.destAddr = destAddr;
        this.router = router;
        this.verbose = verbose;
        this.sequenceNumber = 1;

        setupSocket();

        // Start Sender, Receiver, Processor and Listener threads
        this.processor = new Processor();
        this.listener = new Listener();
        this.sender = new Sender();
        this.receiver = new Receiver();

        this.processor.start();
        this.listener.start();
        this.sender.start();
        this.receiver.start();

        if (connectToServer) {
            // Initiate 3-way handshake with server
            connectToServer();
        }
    }

    // WRITE AND READ METHODS

    // Blocking - reads only one packet
    public String read() throws IOException {
        // Wait for queue to have at least one item
        while(this.receiver.isReadQueueEmpty());

        Packet packet = this.receiver.readFromReadQueue();
        log("TCPSocket.read()", "Reading packet" + packet);

        return new String(packet.getPayload());
    }

    protected void write(Packet packet) throws IOException {
        this.sender.put(packet);
        incrementSeqNum();
    }

    public void write(String data) throws IOException {
        write(data.getBytes(StandardCharsets.UTF_8));
    }

    public void write(byte[] data) throws IOException {
        ArrayList<Packet> packets = createPackets(data);

        for (Packet p : packets) {
            log("TCPSocket.write()", "Sending to Sender Thread packet #" + p.getSequenceNumber());
            this.sender.put(p);
        }
    }

    protected void forward(Packet packet) throws IOException {
        this.processor.put(packet);
    }

    // GETTERS AND SETTERS

    public synchronized void setRouter(InetSocketAddress router) {
        this.router = router;
    }

    public InetSocketAddress getAddress() throws IOException {
        return new InetSocketAddress("127.0.0.1", this.socket.getLocalPort());
    }

    public void close() throws IOException {
        try {
            log("TCPSocket.close()", "Closing...");
            Thread.sleep(300);
            log("TCPSocket.close()", "Sending FIN to " + this.destAddr.getAddress() + ":" + this.destAddr.getPort());

            Packet FINPacket = new Packet(Packet.FIN, this.sequenceNumber, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);

            incrementSeqNum();

            this.sender.put(FINPacket);

            log("TCPSocket.close()", "Waiting for ACK ...");
            waitingForFINACK.acquire();
            log("TCPSocket.close()", "Received ACK.");
            log("TCPSocket.close()", "Waiting for FIN ...");
            waitingForFIN.acquire();
            log("TCPSocket.close()", "Shutting down socket ...");

            this.listener.close();
            this.processor.close();
            this.sender.close();
            this.receiver.close();

            this.listener.join();
            this.processor.join();
            this.sender.join();
            this.receiver.join();

            log("TCPSocket.close()", "Socket Closed.");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // SERVER-SIDE accessible methods

    protected int getSequenceNumber() {
        return this.sequenceNumber;
    }

    // PRIVATE METHODS

    private void log(String method, String str) {
        if (this.verbose) {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd:MM:yyy HH:mm:ss");
            LocalDateTime now = LocalDateTime.now();
            System.out.println(String.format("[%s] %s -- %s", dtf.format(now), method , str));
        }
    }

    private void incrementSeqNum() {
        synchronized(incrementLock) {
            this.sequenceNumber++;
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

    /**
     * Performs the client-side 3-way TCP handshake with server
     */
    private void connectToServer() throws IOException {
        try {
            // Step 1. Send SYN
            // log("TCPSocket.connectToServer()", "[3-WAY] Sending SYN ...");
            Packet SYNPacket = new Packet(Packet.SYN, this.sequenceNumber, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);

            // Send SYN to server and pass packet to Sender thread
            this.sender.put(SYNPacket);

            // Increment sequence number
            incrementSeqNum();

            // Step 2. Wait for Listener thread to receive SYNACK
            synchronized(connectLock) {
                connectLock.wait();
            }

            // log("TCPSocket.connectToServer()", "[3-WAY] Received SYNACK from server ...");

            // Step 3. Send ACK
            Packet ACKPacket = new Packet(Packet.ACK, 1, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);

            // Send ACK directly to server, bypassing the Sender thread.
            this.socket.send(ACKPacket.toDatagramPacket(router));

            // log("TCPSocket.connectToServer()", "[3-WAY] Sending ACK ...");
            // log("TCPSocket.connectToServer()", "Successfully connected to server");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private ArrayList<Packet> createPackets(byte[] data) throws IOException {
        ArrayList<Packet> packets = new ArrayList<>();

        // log("TCPSocket.createPackets()", "Creating packets out of data of length " + data.length);

        // This lock ensures that overized data is broken up into seqential packets
        synchronized(incrementLock) {
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
                byteBuffer.putInt(this.sequenceNumber);
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
        }

        // log("TCPSocket.createPackets()", "Divided data into " + packets.size() + " packets.");

        return packets;
    }

    // Threads

    /**
     *
     */
    private class Sender extends Thread {

        private AtomicBoolean running = new AtomicBoolean(true);
        private PriorityBlockingQueue<Packet> sendBuffer;
        private ConcurrentHashMap<Integer, Packet> ackWaitQueue;
        private int nextSeqNum;
        private int sendBase;

        public Sender() {
            this.sendBase = 1;
            this.nextSeqNum = 1;
            this.sendBuffer = new PriorityBlockingQueue<>(WINDOW_SIZE, new Packet.PacketComparator());
            this.ackWaitQueue = new ConcurrentHashMap<>();
        }

        @Override
        public void run() {
            try {
                while(true) {
                    if (!sendBuffer.isEmpty()) {
                        // log("TCPSocket.Sender.run()", "sendBase = " + this.sendBase + " NextSeqNum = " + nextSeqNum);
                        if (nextSeqNum == sendBuffer.peek().getSequenceNumber()) {
                            Packet packet = sendBuffer.poll();

                            log("TCPSocket.Sender.run()", "Sending packet: " + packet);

                            sendPacket(packet);
                        }
                    }

                    if (!running.get()) {
                        while(!sendBuffer.isEmpty())
                            ackWaitQueue.put(sendBuffer.peek().getSequenceNumber(), sendBuffer.poll());
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            log("TCPSocket.Sender.run()", "Closing Sender");
        }

        public void close() {
            this.running.set(false);
        }

        public void put(Packet packet) throws IOException {
            if (packet.getSequenceNumber() < this.sendBase + WINDOW_SIZE) {
                sendBuffer.add(packet);
            }
        }

        private void sendPacket(Packet packet) throws IOException {
            // Create timer
            // Add it to timer queue
            // Send packet to router
            socket.send(packet.toDatagramPacket(router));
            this.nextSeqNum++;
            // Put packet into ack queue
            ackWaitQueue.put(packet.getSequenceNumber(), packet);
            // Start timer
        }

        private void processACK(Packet ACK) {
            int ACKSeqNum = ACK.getSequenceNumber();
            if (ackWaitQueue.containsKey(ACKSeqNum)) {
                // Stop timer
                // stopTimer(ACKSeqNum);

                // Get packet from wait queue
                Packet packet = ackWaitQueue.get(ACKSeqNum);

                // Remove packet from ACK wait queue
                ackWaitQueue.remove(ACKSeqNum);

                // log("TCPSocket.Sender.processACK()", "sendBase = " + this.sendBase);
                log("TCPSocket.Sender.processACK()", "Successfully received ACK for packet " + packet.getSequenceNumber());

                if (packet.getSequenceNumber() == this.sendBase) {
                    log("TCPSocket.Sender.processACK()", "Incrementing sendBase");
                    this.sendBase++;
                }

                if (packet.getType() == Packet.FIN) {
                    waitingForFINACK.release();
                }
            }
        }

        private void processNAK(Packet packet) {
            log("TCPSocket.Sender.processNAK()", "Processing " + packet);
        }

        private void sendACK(Packet packet) throws IOException {
            log("TCPSocket.Sender.sendACK()", "Sending ACK for " + packet);
            Packet ack = new Packet(Packet.ACK, packet.getSequenceNumber(), destAddr.getAddress(), destAddr.getPort(), new byte[0]);
            socket.send(ack.toDatagramPacket(router));
        }

        private void sendNAK(Packet packet) throws IOException {
            log("TCPSocket.Sender.sendNAK()", "Sending NAK for " + packet);
            Packet nak = new Packet(Packet.NAK, packet.getSequenceNumber(), destAddr.getAddress(), destAddr.getPort(), new byte[0]);
            socket.send(nak.toDatagramPacket(router));
        }

        private void sendSYNACK(Packet packet) throws IOException {
            log("TCPSocket.Sender.sendSYNACK()", "Sending SYNACK for " + packet);
            Packet SYNACKPacket = new Packet(Packet.SYNACK, sequenceNumber, packet.getPeerAddress(), packet.getPeerPort(), new byte[0]);
            incrementSeqNum();
            sendBuffer.add(SYNACKPacket);
        }
    }

    /**
     *
     */
    private class Receiver extends Thread {

        private AtomicBoolean running = new AtomicBoolean(true);
        private PriorityBlockingQueue<Packet> receiveBuffer;
        private PriorityBlockingQueue<Packet> readQueue;
        private int recvBase;

        public Receiver() {
            this.recvBase = 2;
            this.receiveBuffer = new PriorityBlockingQueue<>(WINDOW_SIZE, new Packet.PacketComparator());
            this.readQueue = new PriorityBlockingQueue<>(1000, new Packet.PacketComparator());
        }

        @Override
        public void run() {
            while(true) {
                if (!receiveBuffer.isEmpty()){
                    // log("TCPSocket.Receiver.run()", "recvBase = " + recvBase + " packet seq num = " + receiveBuffer.peek().getSequenceNumber());

                    if (receiveBuffer.peek().getSequenceNumber() == recvBase) {
                        Packet packet = receiveBuffer.poll();
                        log("TCPSocket.Receiver.run()", "Packet #" + packet.getSequenceNumber() + " is now able to be read.");
                        readQueue.add(packet);
                        recvBase++;
                    }
                }

                if (!running.get()) {
                    while(!receiveBuffer.isEmpty())
                        readQueue.add(receiveBuffer.poll());
                    break;
                }
            }
            log("TCPSocket.Receiver.run()", "Closing Receiver");
        }

        public void close() {
            this.running.set(false);
        }

        public void put(Packet packet) throws IOException {
            if (packet.getSequenceNumber() < (this.recvBase + WINDOW_SIZE)) {
                log("TCPSocket.Receiver.put()", "Received packet: " + packet);
                // Send ACK to all packets within the window and any packet previously received
                sender.sendACK(packet);

                if (packet.getSequenceNumber() >= this.recvBase) {
                    // Add packet to receive buffer
                    receiveBuffer.put(packet);
                }
            }
        }

        public boolean isReadQueueEmpty() {
            return this.readQueue.isEmpty();
        }

        public Packet readFromReadQueue() {
            return this.readQueue.poll();
        }
    }

    private class Listener extends Thread {

        private AtomicBoolean running = new AtomicBoolean(true);

        @Override
        public void run() {
            while(running.get()) {
                try {
                    byte[] buf = new byte[Packet.PACKET_SIZE];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);

                    // Set a receive timeout to prevent indefinite blocking
                    socket.setSoTimeout(100);
                    try {
                        // Wait for client to send datagram packet
                        socket.receive(packet);

                        // Create Packet from buffer
                        processor.put(Packet.fromBuffer(buf));
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            log("TCPSocket.Listener.run()", "Closing Listener");
        }

        public void close() {
            this.running.set(false);
        }
    }

    private class Processor extends Thread {

        private AtomicBoolean running = new AtomicBoolean(true);
        private ArrayBlockingQueue<Packet> packetQueue;

        public Processor() {
            packetQueue = new ArrayBlockingQueue<>(1000);
        }

        @Override
        public void run() {
            while(running.get()) {
                try {
                    if (!packetQueue.isEmpty()) {
                        Packet packet = packetQueue.poll();

                        if (packet == null)
                            break;

                        switch(packet.getType()) {
                            case Packet.SYN:
                                sender.sendSYNACK(packet);
                                break;
                            case Packet.SYNACK:
                                synchronized(connectLock) {
                                    connectLock.notify();
                                }
                            case Packet.ACK:
                                sender.processACK(packet);
                                break;
                            case Packet.NAK:
                                sender.processNAK(packet);
                                break;
                            case Packet.FIN:
                                waitingForFIN.release();
                            case Packet.DATA:
                                receiver.put(packet);
                                break;
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        public void close() {
            this.running.set(false);
        }

        private void put(Packet packet) {
            packetQueue.add(packet);
        }
    }
}