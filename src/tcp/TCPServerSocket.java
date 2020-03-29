package tcp;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TCPServerSocket {

    private int port;
    private boolean verbose;
    private DatagramSocket socket;
    private InetSocketAddress router;
    private ArrayBlockingQueue<Packet> synQueue;
    private ArrayBlockingQueue<Packet> ackQueue;
    private HashMap<InetSocketAddress, TCPSocket> clients;

    private Processor processor;
    private Listener listener;

    /**
     * Constructor for a TCPServerSocket.
     *
     * @param port The port number it will listen to for packets.
     * @throws IOException
     */
    public TCPServerSocket(int port) throws IOException {
        this.port = port;

        // Create listening socket
        this.socket = new DatagramSocket(port);
        System.out.println("Server is listening on port " + this.port);

        this.clients = new HashMap<>();
        this.synQueue = new ArrayBlockingQueue<>(1000);
        this.ackQueue = new ArrayBlockingQueue<>(1000);

        // Start listening for packets
        this.listener = new Listener();
        this.processor = new Processor();
        this.listener.start();
        this.processor.start();
    }

    /**
     * Sets up a connection with a client using a 3-way handshake.
     * @return A server-side TCPSocket client that is connected to the requesting client.
     * @throws IOException
     */
    public TCPSocket accept() throws IOException {
        InetSocketAddress requestAddress;
        Packet SYNPacket;
        TCPSocket clientSocket;

        log("TCPServerSocket.accept()", "Accepting new connection");

        // Wait for SYN packet
        while(true) {
            if(!this.synQueue.isEmpty()) {
                SYNPacket  = this.synQueue.poll();
                requestAddress = new InetSocketAddress(SYNPacket.getPeerAddress(), SYNPacket.getPeerPort());

                // Break if a TCPSocket for the requesting address doesn't already exist
                if (!clients.containsKey(requestAddress))
                    break;
            }
        }

        log("TCPServerSocket.accept()", "Received SYN from " + requestAddress + ". Creating client socket ...");

        clientSocket = new TCPSocket(requestAddress, this.router, this.verbose, false);

        // Forward SYN packet to new server-side TCPSocket client
        try {
            clientSocket.forward(SYNPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }

        log("TCPServerSocket.accept()", "Client socket created. Sending SYNACK and waiting for ACK ...");

        // Wait for Listener thread to put in ACK packet
        while(true) {
            if (!ackQueue.isEmpty()) {
                Packet ACK = ackQueue.poll();
                if (ACK.getPeerAddress().equals(requestAddress.getAddress())
                    && ACK.getPeerPort() == requestAddress.getPort()) {
                    break;
                }
            }
        }

        log("TCPServerSocket.accept()", "Received ACK from client. Client is now connected.");

        this.clients.put(requestAddress, clientSocket);

        return clientSocket;
    }

    /**
     * Sets the verbosity of the TCPServerSocket
     * @param verbose
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public boolean isRunning() {
        return this.listener.isAlive() && this.processor.isAlive();
    }

    public void close() {
        try {
        this.listener.close();
        this.processor.close();

        this.listener.join();
        this.processor.join();

        log("TCPServerSocket.close()", "Server Socket closed.");
        this.socket.close();
        } catch(InterruptedException e) {
            e.printStackTrace();
        }
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
            System.out.println(String.format("[%s] %s -- %s", dtf.format(now), method , message));
        }
    }

    /**
     * The Listener thread continuously listens for incoming packets.
     */
    private class Listener extends Thread {
        private AtomicBoolean running = new AtomicBoolean(true);

        @Override
        public void run() {
            while (true) {
                try {
                    // All packets are 1024 bytes in size
                    byte[] buf = new byte[Packet.PACKET_SIZE];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);

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

                    if (router == null) {
                        // Get return address to router
                        router = new InetSocketAddress(packet.getAddress(), packet.getPort());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Stops thread
         */
        public void close() {
            this.running.set(false);
        }
    }

    /**
     * The Processor thread receives a Packet from the Listener thread and determines where it should go
     * depending on its type.
     */
    private class Processor extends Thread {
        private ArrayBlockingQueue<Packet> packetQueue;
        private AtomicBoolean running = new AtomicBoolean(true);

        /**
         * Constructor for the Processor thread
         */
        public Processor() {
            this.packetQueue = new ArrayBlockingQueue<>(1000);
        }

        @Override
        public void run() {
            while(running.get()) {
                if (!packetQueue.isEmpty()) {
                    try {
                        Packet packet = packetQueue.poll();

                        log("TCPServerSocket.Processor.run()", packet.toString());

                        InetSocketAddress peerAddr = new InetSocketAddress(packet.getPeerAddress(), packet.getPeerPort());

                        switch(packet.getType()) {
                            case Packet.SYN:
                                synQueue.add(packet);
                                break;
                            case Packet.ACK:
                                ackQueue.add(packet);
                            case Packet.FIN:
                            case Packet.DATA:
                            case Packet.NAK:
                                forward(packet, peerAddr);
                                break;
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        /**
         * Adds a packet to the packetQueue.
         * @param packet
         */
        public void put(Packet packet) {
            this.packetQueue.add(packet);
        }

        /**
         * Stops thread
         */
        public void close() {
            this.running.set(false);
        }

        /**
         * Forwards incoming packets to the appropriate TCPSocket client.
         * @param packet   Packet of data.
         * @param peerAddr Address of the requesting client.
         * @throws IOException
         */
        private void forward(Packet packet, InetSocketAddress peerAddr) throws IOException {
            if (clients.containsKey(peerAddr)) {
                // Get server-side TCPSocket client
                TCPSocket clientSocket = clients.get(peerAddr);

                if (clientSocket.isRunning()) {
                    // Send packet to client
                    clientSocket.forward(packet);
                } else {
                    // Remove client from list
                    clients.remove(peerAddr);
                }
            }
        }
    }
}