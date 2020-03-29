import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import tcp.*;

public class unittest {

    static Server server = new Server(8080, true);
    static Client client = new Client(8080, new InetSocketAddress("127.0.0.1", 3000), true);
    public static void main(String[] args) {

        test3WayHandshake();
        testWriteAndReadToASocket();
        testClientClose();
        testServerClose();
    }

    public static void test3WayHandshake() {

        System.out.println("---------------------------------------");
        System.out.println("test3WayHandshake");
        System.out.println("---------------------------------------");

        server.start();
        client.start();

        try {
            server.join();
            client.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (client.socket.isRunning())
            System.out.println("\nASSERT: Client is running");
        else
            throw new AssertionError("ASSERT: Client is not running!");

        if (server.client.isRunning())
            System.out.println("\nASSERT: Server client is also running\n");
        else
            throw new AssertionError("ASSERT: Server client is not running!");
    }

    public static void testWriteAndReadToASocket() {
        try {
            Thread.sleep(1000);
            System.out.println("---------------------------------------");
            System.out.println("testWriteAndReadToASocket");
            System.out.println("---------------------------------------");

            String message = "Test Message 1";
            server.client.write(message);

            String response = client.socket.read();

            if (message.equals(response))
                System.out.println("\nASSERT: Message: \"" + response + "\" was successfully sent and received.\n");
            else
                throw new AssertionError("ASSERT: response message doesn't match: \"" + response + "\", \"" + message +"\"");
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void testClientClose() {
        try {
            Thread.sleep(1000);
            System.out.println("---------------------------------------");
            System.out.println("testClose");
            System.out.println("---------------------------------------");

            server.client.close();
            client.socket.close();

            if (!client.socket.isRunning())
                System.out.println("\nASSERT: Client is not running");
            else
                throw new AssertionError("ASSERT: Client is running!");

            if (!server.client.isRunning())
                System.out.println("\nASSERT: Server client is not running\n");
            else
                throw new AssertionError("ASSERT: Server client is running!");

        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void testServerClose() {

        try {
            Thread.sleep(1000);
            System.out.println("---------------------------------------");
            System.out.println("testServerClose");
            System.out.println("---------------------------------------");

            server.server.close();

            if (!server.server.isRunning())
                System.out.println("\nASSERT: Server is not running");
            else
                throw new AssertionError("ASSERT: Server is running!");

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static class Server extends Thread {
        TCPServerSocket server;
        TCPSocket client;
        int port;
        boolean verbose;

        public Server(int port, boolean verbose) {
            this.port = port;
            this.verbose = verbose;
        }

        @Override
        public void run() {
            try {
                this.server = new TCPServerSocket(port);
                this.server.setVerbose(verbose);
                this.client = this.server.accept();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static class Client extends Thread {
        TCPSocket socket;
        int port;
        boolean verbose;
        InetSocketAddress router;

        public Client(int port, InetSocketAddress router, boolean verbose) {
            this.port = port;
            this.router = router;
            this.verbose = verbose;
        }

        @Override
        public void run() {
            try {
                this.socket = new TCPSocket(new InetSocketAddress("127.0.0.1", port), router, verbose);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}