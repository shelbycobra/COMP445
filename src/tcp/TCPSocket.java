package tcp;

import java.util.ArrayList;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import java.time.Instant;
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
    private final static Object incrementLock = new Object();

    private int sequenceNumber;
    private boolean verbose;
    private InetSocketAddress destAddr;
    private InetSocketAddress router;
    private DatagramSocket socket;

    private AtomicBoolean receivedSYNACK = new AtomicBoolean(false);
    private Semaphore waitingForFINACK = new Semaphore(1);
    private Semaphore waitingForFIN = new Semaphore(1);

    private Processor processor;
    private Listener listener;
    private Sender sender;
    private Receiver receiver;

    /**
     * TCPSocket constructor used by the client to create a client-side connection to a specific server.
     * @param destAddr  The address to which the socket will send packets.
     * @param router    The address of the router.
     * @param verbose   Add debugging outputs.
     * @throws IOException
     */
    public TCPSocket(InetSocketAddress destAddr, InetSocketAddress router, boolean verbose) throws IOException {
        this(destAddr, router, verbose, true);
    }

    /**
     * TCPSocket constructor used by the TCPSocket class to create a server-side connection to a specific client.
     * @param destAddr  The address to which the socket will send packets.
     * @param router    The address of the router.
     * @param verbose   Add debugging outputs.
     * @param connectToServer A boolean indicating whether to initiate the 3-way handshake.
     * @throws IOException
     */
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

    /**
     * A blocking read method that returns the payload of one packet at a time.
     * @return The payload of a packet as a string.
     * @throws IOException
     */
    public String read() throws IOException {
        // Wait for queue to have at least one item
        while(this.receiver.isReadQueueEmpty());

        Packet packet = this.receiver.readFromReadQueue();

        return new String(packet.getPayload());
    }

    /**
     * Writes a string of data to the socket to be sent.
     *
     * @param data A String of data.
     * @throws IOException
     */
    public void write(String data) throws IOException {
        write(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Takes an array of bytes to be sent out.
     * Breaks the array into packets of 1024 bytes.
     *
     * @param data
     * @throws IOException
     */
    public void write(byte[] data) throws IOException {
        ArrayList<Packet> packets = createPackets(data);

        for (Packet p : packets) {
            log("TCPSocket.write()", "Sending to Sender Thread packet #" + p.getSequenceNumber());
            this.sender.put(p);
        }
    }

    /**
     * Closes the socket connection.
     * @throws IOException
     */
    public void close() throws IOException {
        try {
            log("TCPSocket.close()", "Closing...");

            this.sender.clear();
            this.sender.put(new Packet(Packet.FIN, this.sequenceNumber, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]));

            log("TCPSocket.close()", "Sending FIN to " + this.destAddr.getAddress() + ":" + this.destAddr.getPort());
            log("TCPSocket.close()", "Waiting for ACK ...");

            waitingForFINACK.acquire();

            log("TCPSocket.close()", "Received ACK.");
            log("TCPSocket.close()", "Waiting for FIN ...");

            waitingForFIN.acquire();

            log("TCPSocket.close()", "Shutting down socket ...");

            this.receiver.clear();

            this.sender.close();
            this.receiver.close();
            this.listener.close();
            this.processor.close();

            this.sender.join();
            this.receiver.join();
            this.listener.join();
            this.processor.join();

            log("TCPSocket.close()", "Socket Closed.");
            this.socket.close();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Indicates whether the TCPSocket object is currently running.
     *
     * @return A boolean
     */
    public boolean isRunning() {
        return this.processor.isAlive() && this.listener.isAlive() && this.sender.isAlive() && this.receiver.isAlive();
    }

    /**
     * Writes a packet build by a TCPServerSocket to be sent out by the client socket.
     *
     * @param packet
     * @throws IOException
     */
    protected void write(Packet packet) throws IOException {
        this.sender.put(packet);
        incrementSeqNum();
    }

    /**
     * Forwards a packet received by a TCPServerSocket to be received by the client socket.
     *
     * @param packet
     * @throws IOException
     */
    protected void forward(Packet packet) throws IOException {
        this.processor.put(packet);
    }

    /**
     * Prints verbose debugging outputs.
     *
     * @param method The current method that the log is coming from.
     * @param message The message to be printed.
     */
    private void log(String method, String message) {
        if (this.verbose) {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd:MM:yyy HH:mm:ss");
            LocalDateTime now = LocalDateTime.now();
            System.out.println(String.format("[%s %s] %s -- %s", this.socket.getLocalAddress().toString() + ":" + this.socket.getLocalPort(), dtf.format(now), method , message));
        }
    }

    /**
     * A thread safe method to increment the sequenceNumber.
     */
    private void incrementSeqNum() {
        synchronized(incrementLock) {
            this.sequenceNumber++;
        }
    }

    /**
     * Binds the channel to either a random port or a specified source port,
     * then connects it to the destination address and port number.
     *
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
        // Step 1. Send SYN
        log("TCPSocket.connectToServer()", "[3-WAY] Sending SYN ...");
        Packet SYNPacket = new Packet(Packet.SYN, this.sequenceNumber, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);

        // Send SYN to server and pass packet to Sender thread
        this.sender.put(SYNPacket);

        // Increment sequence number
        incrementSeqNum();

        // Step 2. Wait for Listener thread to receive SYNACK
        while(!receivedSYNACK.get());

        log("TCPSocket.connectToServer()", "[3-WAY] Received SYNACK from server ...");

        // Step 3. Send ACK
        Packet ACKPacket = new Packet(Packet.ACK, 1, this.destAddr.getAddress(), this.destAddr.getPort(), new byte[0]);

        // Send ACK directly to server, bypassing the Sender thread.
        this.socket.send(ACKPacket.toDatagramPacket(router));

        log("TCPSocket.connectToServer()", "[3-WAY] Sending ACK ...");
        log("TCPSocket.connectToServer()", "Successfully connected to server");
    }

    /**
     * Takes an array of bytes and packages them into 1 or more Packets of 1024 bytes (or less).
     *
     * @param data
     * @return An ArrayList of Packets
     * @throws IOException
     */
    private ArrayList<Packet> createPackets(byte[] data) throws IOException {
        ArrayList<Packet> packets = new ArrayList<>();

        log("TCPSocket.createPackets()", "Creating packets out of data of length " + data.length);

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

        log("TCPSocket.createPackets()", "Divided data into " + packets.size() + " packets.");

        return packets;
    }

    /**
     * A Sender thread that handles the sender side of the socket.
     * It implements the Selective Repeat protocol for ensuring reliable data transmission.
     * It receives Packets build from above and sends them in order.
     */
    private class Sender extends Thread {

        private final double ALPHA = 0.125;
        private final double BETA = 0.25;

        private int nextSeqNum;
        private int sendBase;
        private double estimatedRTT;
        private double devianceRTT;
        private int timeoutInterval;

        private AtomicBoolean running = new AtomicBoolean(true);

        private PriorityBlockingQueue<Packet> sendBuffer;
        private ConcurrentHashMap<Integer, Packet> ackWaitQueue;
        private ConcurrentHashMap<Integer, PacketTimer> timerQueue;

        private Timer timerScheduler;

        /**
         * PacketTimer class that keeps track of individual timers for each packet sent.
         */
        private class PacketTimer extends TimerTask {

            private final int MAX_TIMEOUTS = 20;

            private long startTime;
            private int sequenceNumber;
            private int timeouts;

            /**
             * Constructor for a PacketTimer. Receives the sequence number for the packet it is tracking.
             * @param sequenceNumber
             */
            public PacketTimer(int sequenceNumber) {
                super();
                this.sequenceNumber = sequenceNumber;
                this.startTime = Instant.now().toEpochMilli();
                this.timeouts = 0;
            }

            public long getStartTime() {
                return startTime;
            }

            /**
             * Resends the packet it is tracking whenever a timeout occurs.
             * Will timeout up to MAX_TIMEOUTS number of times until an ACK is received.
             */
            @Override
            public void run() {
                try {
                    if (timeouts > 0) {
                        log("TCPSocket.Sender.PacketTimer.run()", "ACK timeout for packet #" + this.sequenceNumber + ". Resending ... ");
                        Packet packet = ackWaitQueue.get(this.sequenceNumber);
                        socket.send(packet.toDatagramPacket(router));
                    }

                    timeouts++;

                    if (timeouts >= MAX_TIMEOUTS) {
                        this.cancel();
                        log("TCPSocket.Sender.PacketTimer.run()", "Max timeouts reached for packet #" + this.sequenceNumber);

                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Contructor for the Sender thread.
         */
        public Sender() {
            this.sendBase = 1;
            this.nextSeqNum = 1;
            this.sendBuffer = new PriorityBlockingQueue<>(WINDOW_SIZE, new Packet.PacketComparator());
            this.ackWaitQueue = new ConcurrentHashMap<>();
            this.timerQueue = new ConcurrentHashMap<>();
            this.timerScheduler = new Timer();

            this.estimatedRTT = 0;
            this.devianceRTT = 0;
            this.timeoutInterval = 1000; // Start timeout at 1 second.
        }

        /**
         * Processes Packets in the sendBuffer and sends them in order.
         */
        @Override
        public void run() {
            try {
                while(true) {
                    if (!sendBuffer.isEmpty()) {

                        if (nextSeqNum == sendBuffer.peek().getSequenceNumber()) {
                            Packet packet = sendBuffer.poll();
                            sendPacket(packet);
                        }
                    }

                    if (!running.get()) {
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            log("TCPSocket.Sender.run()", "Closing Sender");
        }

        /**
         * Called during socket shutdown.
         *
         * A blocking method that clears all packets in the send buffer
         * and waits until all packets have been ACK'd.
         *
         * @throws IOException
         */
        public void clear() throws IOException {
            log ("TCPSocket.Sender.clear()", "Clearing all send data");
            while(!sendBuffer.isEmpty()){
                sendPacket(sendBuffer.poll());
            }
            ackWaitQueue.clear();
            log("TCPSocket.Sender.run()", "Cancelling all timers.");
            timerScheduler.cancel();
        }

        /**
         * Stops the thread.
         */
        public void close() {
            running.set(false);
        }

        /**
         * Puts a Packet received from above into the sendBuffer only if the
         * sequence number is within the send window.
         *
         * @param packet
         * @throws IOException
         */
        public void put(Packet packet) throws IOException {
            if (packet.getSequenceNumber() < this.sendBase + WINDOW_SIZE) {
                sendBuffer.add(packet);
            }
        }

        /**
         * Sends a packet via the TCPSocket's UDP socket.
         * Starts a timer for the packet.
         *
         * @param packet
         * @throws IOException
         */
        private void sendPacket(Packet packet) throws IOException {
            // Create PacketTimer
            PacketTimer packetTimer = new PacketTimer(packet.getSequenceNumber());

            // Add it to timer queue
            timerQueue.put(packet.getSequenceNumber(), packetTimer);

            // Send packet to router
            log("TCPSocket.Sender.sendPacket()", "Sending packet: " + packet);
            socket.send(packet.toDatagramPacket(router));
            this.nextSeqNum++;

            // Put packet into ack queue
            ackWaitQueue.put(packet.getSequenceNumber(), packet);

            // Start timer
            try {
                timerScheduler.schedule(packetTimer, 0, this.timeoutInterval);
                log("TCPSocket.Sender.sendPacket()", "Starting timer with interval of " + this.timeoutInterval);
            } catch (IllegalStateException e) {
                log("TCPSocket.Sender.sendPacket()", "Timer already cancelled.");
            }
        }

        /**
         * Processes an ACK received from above.
         *
         * If a packet has not yet been ACK'd, their timer is cancelled and the timout interval is recalculated.
         * If the packet is a SYNACK, then it tells the TCPSocket to continue with the 3-way handshalke.
         * If the packet is an ACK for a FIN packet, then it tells the TCPSocket to continue with shutdown.
         *
         * @param ACK
         */
        private void processACK(Packet ACK) {
            int ACKSeqNum = ACK.getSequenceNumber();

            if (ackWaitQueue.containsKey(ACKSeqNum)) {
                Packet packet = ackWaitQueue.get(ACKSeqNum);
                PacketTimer timer = timerQueue.get(ACKSeqNum);

                log("TCPSocket.Sender.processACK()", "Packet ACK'd: " + packet + " with packet " + ACK);

                // Stop timer and remove from timerScheduler
                timer.cancel();
                timerScheduler.purge();

                timerQueue.remove(ACKSeqNum);
                ackWaitQueue.remove(ACKSeqNum);

                // Calculate timeout using the sample RTT
                calculateTimeout(Instant.now().toEpochMilli() - timer.getStartTime());

                // Increment sendBase
                if (packet.getSequenceNumber() == this.sendBase)
                    this.sendBase++;

                // If the ACK is a SYNACK, then allow the 3-way handshake to continue
                if (ACK.getType() == Packet.SYNACK)
                    receivedSYNACK.set(true);

                // If the ACK is a for a FIN, then allow shutdown to continue.
                if (packet.getType() == Packet.FIN)
                    waitingForFINACK.release();
            }
        }

        /**
         * Calculates the timeoutInterval every time an ACK is received for a packet.
         * @param sampleRTT
         */
        private synchronized void calculateTimeout(long sampleRTT) {
            this.estimatedRTT = (1.0-ALPHA) * this.estimatedRTT + ALPHA * sampleRTT;
            this.devianceRTT = (1.0-BETA) * this.devianceRTT + BETA * Math.abs(sampleRTT * 1.0 - this.estimatedRTT);
            this.timeoutInterval = (int) (this.estimatedRTT + 4.0 * this.devianceRTT);
        }

        /**
         * Sends a SYNACK when a TCPSocket receives a SYN packet.
         * @param packet
         * @throws IOException
         */
        private void sendSYNACK(Packet packet) throws IOException {
            log("TCPSocket.Sender.sendSYNACK()", "Sending SYNACK for " + packet);
            Packet SYNACKPacket = new Packet(Packet.SYNACK, sequenceNumber, packet.getPeerAddress(), packet.getPeerPort(), new byte[0]);
            incrementSeqNum();
            sendBuffer.add(SYNACKPacket);
        }
    }

    /**
     * A Receiver thread that handles the receiver side of the socket.
     * It implements the Selective Repeat protocol for ensuring reliable data transmission.
     * It receives Packets from above, sends ACKs and allows the TCPSocket to read them in order.
     */
    private class Receiver extends Thread {

        private AtomicBoolean running = new AtomicBoolean(true);
        private PriorityBlockingQueue<Packet> receiveBuffer;
        private PriorityBlockingQueue<Packet> readQueue;
        private int recvBase;

        /**
         * Constructor for the Receiver thread
         */
        public Receiver() {
            this.recvBase = 2;
            this.receiveBuffer = new PriorityBlockingQueue<>(WINDOW_SIZE, new Packet.PacketComparator());
            this.readQueue = new PriorityBlockingQueue<>(1000, new Packet.PacketComparator());
        }

        /**
         * Receives packets in the receiveBuffer and returns them in order of sequence number to the TCPSocket client.
         */
        @Override
        public void run() {
            while(true) {
                if (!receiveBuffer.isEmpty()){
                    if (receiveBuffer.peek().getSequenceNumber() == recvBase) {
                        Packet packet = receiveBuffer.poll();
                        readQueue.add(packet);
                        recvBase++;
                    }
                }

                if (!running.get()) {
                    break;
                }
            }

            log("TCPSocket.Receiver.run()", "Closing Receiver");
        }

        /**
         * Clears all packets waiting to be read. Called during socket shutdown.
         */
        public void clear() {
            log ("TCPSocket.Receiver.clear()", "Clearing all read data");
            while(!receiveBuffer.isEmpty())
                readQueue.add(receiveBuffer.poll());
        }

        /**
         * Stops the Receiver thread.
         */
        public void close() {
            running.set(false);
        }

        /**
         * Receives a packet from above.
         * Sends ACK for any packet with a sequence number less than or within the receive window.
         * Puts any Packet with a sequence number within the receive window into the receiveBuffer.
         *
         * @param packet
         * @throws IOException
         */
        public void put(Packet packet) throws IOException {

            if (packet.getSequenceNumber() < (this.recvBase + WINDOW_SIZE)) {
                log("TCPSocket.Receiver.put()", "Received packet: " + packet);

                // ACK all packets within the window and any packet previously received
                sendACK(packet);

                if (packet.getSequenceNumber() >= this.recvBase) {
                    // Add packet to receive buffer
                    receiveBuffer.put(packet);
                }
            }
        }

        /**
         * Indicates whether the readQueue is empty.
         *
         * @return A boolean
         */
        public boolean isReadQueueEmpty() {
            return this.readQueue.isEmpty();
        }

        /**
         * Returns the top most packet of the read queue.
         *
         * @return One Packet from the read queue
         */
        public Packet readFromReadQueue() {
            return this.readQueue.poll();
        }

        /**
         * Sends an ACK for a packet received from above.
         * @param packet
         * @throws IOException
         */
        private void sendACK(Packet packet) throws IOException {
            log("TCPSocket.Receiver.sendACK()", "Sending ACK for " + packet);
            Packet ack = new Packet(Packet.ACK, packet.getSequenceNumber(), destAddr.getAddress(), destAddr.getPort(), new byte[0]);
            socket.send(ack.toDatagramPacket(router));
        }
    }

    /**
     * The Listener thread continuously listens for incoming packets.
     */
    private class Listener extends Thread {

        private AtomicBoolean running = new AtomicBoolean(true);

        @Override
        public void run() {
            while(true) {
                try {
                    byte[] buf = new byte[Packet.PACKET_SIZE];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);

                    // Set a receive timeout to prevent indefinite blocking
                    socket.setSoTimeout(100);
                    try {
                        if (!running.get()) {
                            break;
                        }
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

        /**
         * Stops the thread
         */
        public void close() {
            running.set(false);
        }
    }

    /**
     * The Processor thread receives a Packet from the Listener thread and determines where it should go
     * depending on its type.
     */
    private class Processor extends Thread {

        private AtomicBoolean running = new AtomicBoolean(true);
        private ArrayBlockingQueue<Packet> packetQueue;

        public Processor() {
            packetQueue = new ArrayBlockingQueue<>(1000);
        }

        @Override
        public void run() {
            while(true) {
                try {
                    if (!packetQueue.isEmpty()) {
                        Packet packet = packetQueue.poll();

                        log("TCPSocket.Processor.run()", packet.toString());

                        switch(packet.getType()) {
                            case Packet.SYN:
                                sender.sendSYNACK(packet);
                                break;
                            case Packet.SYNACK:
                                receiver.put(packet);
                            case Packet.ACK:
                                sender.processACK(packet);
                                break;
                            case Packet.FIN:
                                waitingForFIN.release();
                                sender.clear();
                            case Packet.DATA:
                                receiver.put(packet);
                                break;
                        }
                    }

                    if (!running.get()) {
                        break;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            log("TCPSocket.Processor.run()", "Closing Processor");

        }

        /**
         * Stops the thread.
         */
        public void close(){
            running.set(false);
        }

        private void put(Packet packet) {
            packetQueue.add(packet);
        }
    }
}