import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

public class httpfs {

    private TCPSocket server;
    private String root;
    private int port;
    private boolean verbose;
    private ReentrantLock mutex;

    public httpfs(String[] args) {
        this.root = ".";
        this.port = 8080;
        this.verbose = false;
        this.mutex = new ReentrantLock();

        parseArgs(args);
    }

    public void listen() throws IOException{

        server = new TCPSocket(this.port);
        System.out.println("\nServer is listening on port " + this.port + "\n");

        try {
            int id = 1;

            while(true) {
                TCPClientSocket client = server.accept();

                SocketClient clientThread = new SocketClient(client, this.mutex, this.verbose, this.root, id);
                clientThread.start();
                id++;
            }

        } catch(Exception e) {
            System.out.println(e);
        }
    }

    private void parseArgs(String[] args){
        int index = 0;

        while (index < args.length) {
            if (args[index].equals("-v")) {
                index++;
                this.verbose = true;
                continue;
            }
            if (args[index].equals("-p")) {
                index++;
                try {
                    this.port = Integer.parseInt(args[index]);
                } catch(Exception e) {
                    System.out.println("ERROR: Invalid port \"" + args[index] + "\" .");
                    System.exit(1);
                }
                index++;
                continue;
            }
            if (args[index].equals("-d")) {
                index++;
                String dir = args[index];
                this.root = dir;

                if (this.root.endsWith("/"))
                    this.root = this.root.substring(0, this.root.length()-1);

                index++;
                continue;
            }

            System.out.println("ERROR: Invalid argument \"" + args[index] + "\" .\nUse `java httpfs help` for more infrmation.\n");
            System.exit(1);
        }

        if (this.verbose) {
            System.out.println("\nArguments set:"
            + "\n\tRoot: " + this.root
            + "\n\tPort: " + this.port);
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("help"))
            {
                System.out.println("\nhttpfs is a simple file server.\n"
                + "usage: httpfs [-v] [-p PORT] [-d PATH-TO-DIR]\n\n"
                + "-v Prints debugging messages.\n"
                + "-p Specifies the port number that the server will listen and serve at.\n"
                + "   Default is 8080.\n"
                + "-d Specifies the directory that the server will use to read/write\n"
                + "   requested files. Default is the current directory when launching the\n"
                + "   application.\n");
            }
            else
            {
                httpfs httpfs = new httpfs(args);
                httpfs.listen();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}