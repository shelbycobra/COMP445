package tcp;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

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

    private int port;
    private boolean verbose;
    private DatagramSocket socket;
    private InetSocketAddress router;
    private InetSocketAddress requestAddr;
    private ArrayBlockingQueue<Packet> packetQueue;
    private ArrayBlockingQueue<Packet> synQueue;
    private ArrayBlockingQueue<Packet> ackQueue;
    private HashMap<InetSocketAddress, TCPSocket> clients;

    private Processor processor;
    private Listener listener;

    public TCPServerSocket(int port) throws IOException {
        this.port = port;

        // Create listening socket
        this.socket = new DatagramSocket(port);
        System.out.println("Server is listening on port " + this.port);

        // Initialize map of clients
        this.clients = new HashMap<>();
        this.packetQueue = new ArrayBlockingQueue<>(1000);
        this.synQueue = new ArrayBlockingQueue<>(1000);
        this.ackQueue = new ArrayBlockingQueue<>(1000);

        // Start listening for packets
        this.listener = new Listener();
        this.processor = new Processor();
        this.listener.start();
        this.processor.start();
    }

    public TCPSocket accept() throws IOException {
        try {
            // Thread.sleep(200);
            log("TCPServerSocket.accept()", "Accepting new connection");

            // Wait for SYN packet
            while(synQueue.isEmpty());
            Packet SYNPacket = synQueue.poll();

            setRequestAddress(new InetSocketAddress(SYNPacket.getPeerAddress(), SYNPacket.getPeerPort()));

            log("TCPServerSocket.accept()", "Received SYN from " + requestAddr + ". Creating client socket ...");

            TCPSocket clientSocket = new TCPSocket(this.requestAddr, this.router, this.verbose, false);

            // Forward SYN packet to new server-side TCPSocket client so that it can send a SYNACK packet to the requesting client.
            clientSocket.forward(SYNPacket);

            log("TCPServerSocket.accept()", "Client socket created. Sending SYNACK ...");
            log("TCPServerSocket.accept()", "Waiting for ACK ...");

            // Wait for Listener thread to put in corresponding ACK packet
            while(true) {
                if (!ackQueue.isEmpty()) {
                    Packet ACK = ackQueue.poll();
                    if (ACK.getPeerAddress().equals(this.requestAddr.getAddress())
                        && ACK.getPeerPort() == this.requestAddr.getPort()) {
                        break;
                    }
                }
            }

            log("TCPServerSocket.accept()", "Received ACK from client. Client is now connected.");

            this.clients.put(requestAddr, clientSocket);
            setRequestAddress(null);

            return clientSocket;

        } catch (IOException e) {
            e.printStackTrace();
        } 
        /*catch (InterruptedException e) {
            e.printStackTrace();
        }*/

        return null;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    private void log(String method, String str) {
        if (this.verbose) {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd:MM:yyy HH:mm:ss");
            LocalDateTime now = LocalDateTime.now();
            System.out.println(String.format("[%s] %s -- %s", dtf.format(now), method , str));
        }
    }

    private synchronized void setRequestAddress(InetSocketAddress addr) {
        this.requestAddr = addr;
    }

    private class Listener extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    // All packets are 1024 bytes in size
                    byte[] buf = new byte[Packet.PACKET_SIZE];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);

                    // Wait for packet
                    socket.receive(packet);

                    if (router == null) {
                        // Get return address to router
                        router = new InetSocketAddress(packet.getAddress(), packet.getPort());
                    }

                    // Create Packet from buffer
                    packetQueue.add(Packet.fromBuffer(buf));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class Processor extends Thread {
        @Override
        public void run() {
            while(true) {
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

        private void forward(Packet packet, InetSocketAddress peerAddr) throws IOException {
            // Wait until the client socket has been added to the clients map.
            while (!clients.containsKey(peerAddr));

            // Get address of server-side TCPSocket client
            System.out.println("PEER ADDRESS = " + peerAddr);
            TCPSocket clientSocket = clients.get(peerAddr);

            // Send packet to client
            clientSocket.forward(packet);
        }
    }
}