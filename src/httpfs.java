import java.io.IOException;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import tcp.TCPServerSocket;
import tcp.TCPSocket;

public class httpfs {

    private TCPServerSocket server;
    private String root;
    private int port;
    private boolean verbose;
    private boolean veryVerbose;
    private ReentrantLock mutex;

    public static void main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("help"))
            {
                System.out.println("\nhttpfs is a simple file server.\n"
                + "usage: httpfs [-v|-vv] [-p PORT] [-d PATH-TO-DIR]\n\n"
                + "-v  Prints debugging messages.\n"
                + "-vv Adds TCPServerSocket debugging messages to regular debugging mesages.\n"
                + "-p  Specifies the port number that the server will listen and serve at.\n"
                + "    Default is 8080.\n"
                + "-d  Specifies the directory that the server will use to read/write\n"
                + "    requested files. Default is the current directory when launching the\n"
                + "    application.\n");
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

    public httpfs(String[] args) {
        this.root = ".";
        this.port = 8080;
        this.verbose = false;
        this.mutex = new ReentrantLock();

        parseArgs(args);
    }

    private void parseArgs(String[] args){
        int index = 0;

        while (index < args.length) {
            if (args[index].equals("-v")) {
                index++;
                this.verbose = true;
                continue;
            }
            if (args[index].equals("-vv")) {
                index++;
                this.verbose = true;
                this.veryVerbose = true;
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

    public void listen() throws IOException{

        server = new TCPServerSocket(this.port);

        server.setVerbose(this.veryVerbose);

        try {
            int id = 1;

            while(true) {
                // Accept incoming client requests
                TCPSocket client = server.accept();

                // Handle client request
                ClientThread clientThread = new ClientThread(client, this.mutex, this.verbose, this.root, id);
                clientThread.start();
                id++;
            }

        } catch(Exception e) {
            e.printStackTrace();
        }

    }

    class ClientThread extends Thread {
        private final static String CONTENT_LENGTH = "Content-Length: ";
        private final static String HTTP_VERSION = "HTTP/";
        private final static String GET = "GET";
        private final static String POST = "POST";

        private TCPSocket socket;
        private ReentrantLock mutex;
        private boolean verbose;
        private String logHeader;
        private String request;
        private String headers;
        private String body;
        private String root;
        private int id;

        public ClientThread(TCPSocket socket, ReentrantLock mutex, boolean verbose, String root, int id) throws IOException{
            this.socket = socket;
            this.mutex = mutex;
            this.verbose = verbose;
            this.root = root;
            this.id = id;
            this.logHeader = "ClientThread [" + this.id + "]";
            this.headers = "";
            this.request = "";
            this.body = "";
        }

        public void run(){
            try {
                log(this.logHeader, "is running.");

                parseFullRequest();
                parseRequestLine();

                this.socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void log(String method, String message) {
            if (this.verbose) {
                DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd:MM:yyy HH:mm:ss");
                LocalDateTime now = LocalDateTime.now();
                System.out.println(String.format("[%s] %s -- %s", dtf.format(now), method , message));
            }
        }

        private void parseFullRequest() throws IOException{
            int contentLength = 0;
            StringBuilder headers = new StringBuilder();
            StringBuilder body = new StringBuilder();
            String request = this.socket.read();
            String[] requestLines = request.split("\n");

            if (!requestLines[0].contains(GET) && !requestLines[0].contains(POST))
                sendErrorResponse(400);

            boolean isHeader = true;
            int pos = 1;
            while (pos < requestLines.length) {
                String line = requestLines[pos].replaceFirst("\\s++$", "");
                pos++;

                if (isHeader)
                    headers.append(line).append("\r\n");
                else
                    body.append(line).append("\r\n");

                if (line.contains(CONTENT_LENGTH))
                    contentLength = Integer.parseInt(line.substring(CONTENT_LENGTH.length()));

                if (line.equals(""))
                    isHeader = false;
            }

            if (body.length() < contentLength - 2) {

                // remove the appended '\r\n' at the end of the body.
                body.deleteCharAt(body.length() - 1);
                body.deleteCharAt(body.length() - 1);

                while (body.length() < contentLength - 2) {
                    // System.out.println("**** C BUILDING RESPONSE **** " + body.length() + " and " + (contentLength - 2));
                    body.append(socket.read());
                }
            }

            this.request = requestLines[0];
            this.headers = headers.toString();
            this.body = body.toString().substring(0, contentLength);

            log(this.logHeader, "is requesting:"
                + "\n\n" + this.request
                + "\n" + this.headers + this.body + "\n");
        }

        private void parseRequestLine() {
            int indexOfHttpVersion = this.request.indexOf(HTTP_VERSION);
            int indexOfQuery = this.request.indexOf("?");
            Path rootPath = Paths.get(this.root).toAbsolutePath().normalize();

            if (this.request.indexOf(GET) == 0)
            {
                String path = null;

                // Find path in the request line
                if (indexOfQuery != -1)
                    path = this.request.substring(GET.length()+1, indexOfQuery).trim();
                else
                    path = this.request.substring(GET.length()+1, indexOfHttpVersion-1).trim();

                // Get absolute normalized path of the requested path
                Path requestedPath = Paths.get(this.root + path).toAbsolutePath().normalize();

                // Process request
                if (path.equals("/"))
                    sendList(rootPath);
                else if (!requestedPath.startsWith(rootPath))
                    sendErrorResponse(403);
                else
                    sendFileContents(requestedPath);
            }
            else if (this.request.indexOf(POST) == 0)
            {
                // Get path in the request line
                String path = this.request.substring(POST.length()+1, indexOfHttpVersion).trim();

                // Get absolute normalized path of the requested path
                Path requestedPath = Paths.get(this.root + path).toAbsolutePath().normalize();

                // Process request
                if (path.equals("/") || !requestedPath.startsWith(rootPath))
                    sendErrorResponse(403);
                else
                    createOrOverrideFile(requestedPath);
            } else {
                sendErrorResponse(400);
            }
        }

        private void sendList(Path path) {
            log(this.logHeader, "Sending a list of files from " + path);

            try {
                StringBuilder list = new StringBuilder();
                File rootDir = path.toFile();

                mutex.lock();
                log(this.logHeader, "Entering List CS");
                String[] entries = rootDir.list();
                log(this.logHeader, "Leaving List CS");
                mutex.unlock();

                for (String e : entries)
                    list.append(e).append("\r\n");

                log(this.logHeader, list.toString());

                String response = "HTTP/1.0 200 OK\r\n"
                    + "Content-Type: text/plain\r\n"
                    + CONTENT_LENGTH + list.length() + "\r\n"
                    + "\r\n"
                    + list.toString();

                log(this.logHeader, "Sending response.\n" + response);

                this.socket.write(response);
            } catch (Exception e) {
                sendErrorResponse(500);
                e.printStackTrace();
            }
        }

        private void sendFileContents(Path path) {
            log(this.logHeader, "Sending contents of " + path + ".\n");

            try {
                StringBuilder contents = new StringBuilder();
                BufferedReader reader = null;
                File file = path.toFile();

                if (!file.exists())
                    sendErrorResponse(404);
                else if (!file.canRead())
                    sendErrorResponse(403);
                else if (file.isDirectory())
                    sendList(path);
                else {
                    // File exists and has read permission
                    reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));

                    mutex.lock();
                    log(this.logHeader, "Entering List CS");

                    String line;
                    while ((line = reader.readLine()) != null) {
                        contents.append(line.replaceFirst("\\s++$", "")).append("\r\n");
                    }

                    log(this.logHeader, "Leaving Read CS");
                    mutex.unlock();

                    String response = "HTTP/1.0 200 OK\r\n"
                        + "Content-Type: text/plain\r\n"
                        + CONTENT_LENGTH + contents.length() + "\r\n"
                        + "\r\n"
                        + contents.toString();
                    this.socket.write(response);
                    reader.close();

                    log(this.logHeader, contents.toString());
                }

            } catch (Exception e) {
                sendErrorResponse(500);
                e.printStackTrace();
            }
        }

        private void createOrOverrideFile(Path path) {
            try {
                FileOutputStream fout = null;
                File file = path.toFile();
                boolean override = this.headers.contains("Overwrite: true");
                StringBuilder response = new StringBuilder();

                if (!file.exists()) {
                    file.getParentFile().mkdirs();
                    response.append("HTTP/1.0 201 Created\r\n");
                    log(this.logHeader, "Creating " + path);
                }
                else if (file.canWrite()) {
                    response.append("HTTP/1.0 200 OK\r\n");
                    log(this.logHeader, "Modifying " + path);
                }
                else
                    sendErrorResponse(403);

                mutex.lock();

                log(this.logHeader, "Entering Write CS.");

                // Enter critical section
                fout = new FileOutputStream(file, !override);

                for (Character c : this.body.toCharArray())
                    fout.write(c);

                fout.close();
                //Exit critical section

                log(this.logHeader, "Leaving Write CS.");

                mutex.unlock();

                this.socket.write(response.toString());
            } catch (Exception e) {
                sendErrorResponse(500);
                e.printStackTrace();
            }
        }

        private void sendErrorResponse(int code) {
            log(this.logHeader, "Sending Error response " + code);

            try {
                switch(code) {
                    case 400:
                        this.socket.write("HTTP/1.0 400 Bad Request\r\n" + this.headers);
                        break;
                    case 403:
                        this.socket.write("HTTP/1.0 403 Forbidden\r\n" + this.headers);
                        break;
                    case 404:
                        this.socket.write("HTTP/1.0 404 Not Found\r\n" + this.headers);
                        break;
                    case 500:
                    default:
                        this.socket.write("HTTP/1.0 500 Internal Server Error\r\n" + this.headers);
                        break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}