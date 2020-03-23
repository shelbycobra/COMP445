import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

class SocketClient extends Thread {
        private final static String CONTENT_LENGTH = "Content-Length: ", HTTP_VERSION = "HTTP/",
            GET = "GET", POST = "POST";

        private Socket client;
        private BufferedReader in;
        private PrintWriter out;
        private ReentrantLock mutex;
        private boolean verbose;
        private String logHeader;
        private String request;
        private String headers;
        private String body;
        private String root;
        private int id;

        public SocketClient(Socket client, ReentrantLock mutex, boolean verbose, String root, int id) throws IOException{
            this.client = client;
            this.mutex = mutex;
            this.in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            this.out = new PrintWriter(client.getOutputStream());
            this.verbose = verbose;
            this.root = root;
            this.id = id;
            this.logHeader = "Client [" + this.id + "] ";
            this.headers = "";
            this.request = "";
            this.body = "";
        }

        public void run(){
            try {
                if (this.verbose)
                    System.out.println(this.logHeader + "is running.\n");

                parseFullRequest();
                parseRequestLine();

                client.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void parseRequestLine() {
            int indexOfHttpVersion = this.request.indexOf(HTTP_VERSION);
            int indexOfQuery = this.request.indexOf("?");
            String path = null;

            System.out.println(this.request);

            if (this.request.indexOf(GET) == 0) {
                if (indexOfQuery != -1)
                    path = this.request.substring(GET.length()+1, indexOfQuery).trim();
                else
                    path = this.request.substring(GET.length()+1, indexOfHttpVersion-1).trim();

                if (path.equals("/"))
                    sendList(this.root);
                else if (path.contains(".."))
                    sendErrorResponse(403);
                else
                    sendFileContents(path);
            } else if (this.request.indexOf(POST) == 0) {
                path = this.request.substring(POST.length()+1, indexOfHttpVersion).trim();

                if (path.equals("/") || path.contains(".."))
                    sendErrorResponse(403);
                else
                    createOrOverrideFile(path);
            } else {
                sendErrorResponse(400); // Bad request
            }
        }

        private void sendList(String path) {
            if (this.verbose)
                System.out.println(this.logHeader + "Sending a list of files from " + path);

            try {
                StringBuilder list = new StringBuilder();
                File rootDir = new File(path);

                mutex.lock();
                if (this.verbose)
                    System.out.println(this.logHeader + "Entering List CS");
                String[] entries = rootDir.list();
                if (this.verbose)
                    System.out.println(this.logHeader + "Leaving List CS");
                mutex.unlock();

                for (String e : entries)
                    list.append(e).append("\r\n");

                if (this.verbose)
                    System.out.println(list);

                out.print("HTTP/1.0 200 OK\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-length: " + list.length() + "\r\n"
                    + "\r\n"
                    + list.toString());
                out.flush();
            } catch (Exception e) {
                sendErrorResponse(500);
                e.printStackTrace();
            }
        }

        private void sendFileContents(String path) {
            if (this.verbose)
                System.out.println(this.logHeader + "Sending contents of " + this.root + path + ".\n");

            try {
                StringBuilder contents = new StringBuilder();
                FileInputStream fin = null;
                File file = new File(this.root + path);

                if (!file.exists())
                    sendErrorResponse(404);
                else if (!file.canRead())
                    sendErrorResponse(403);
                else if (file.isDirectory())
                    sendList(this.root + path);
                else {
                    // File exists and has read permission
                    fin = new FileInputStream(file);

                    mutex.lock();
                    if (this.verbose)
                        System.out.println(this.logHeader + "Entering List CS");

                    int c;
                    while ((c = fin.read()) != -1) {
                        contents.append((char)c);
                    }

                    if (this.verbose)
                        System.out.println(this.logHeader + "Leaving Read CS");
                    mutex.unlock();

                    out.print("HTTP/1.0 200 OK\r\n"
                        + "Content-type: text/plain\r\n"
                        + "Content-length: " + contents.length() + "\r\n"
                        + "\r\n"
                        + contents.toString());
                    out.flush();
                    fin.close();

                    if (this.verbose)
                        System.out.println(this.logHeader + contents);
                }

            } catch (Exception e) {
                sendErrorResponse(500);
                e.printStackTrace();
            }
        }

        private void createOrOverrideFile(String path) {
            try {
                FileOutputStream fout = null;
                File file = new File(this.root + path);
                boolean override = this.headers.contains("Overwrite: true");
                StringBuilder response = new StringBuilder();

                if (!file.exists()) {
                    file.getParentFile().mkdirs();
                    response.append("HTTP/1.0 201 Created\r\n");
                    if (this.verbose)
                        System.out.println(this.logHeader + "Creating " + this.root + path + ".\n");
                }
                else if (file.canWrite()) {
                    response.append("HTTP/1.0 200 OK\r\n");
                    if (this.verbose)
                        System.out.println(this.logHeader + "Modifying " + this.root + path + ".\n");
                }
                else
                    sendErrorResponse(403);

                mutex.lock();

                if (this.verbose)
                    System.out.println(this.logHeader + "Entering Write CS.");

                // Enter critical section
                fout = new FileOutputStream(file, !override);

                for (Character c : this.body.toCharArray())
                    fout.write(c);

                fout.close();
                //Exit critical section

                if (this.verbose)
                    System.out.println(this.logHeader + "Leaving Write CS.");

                mutex.unlock();

                out.print(response);
                out.flush();

            } catch (Exception e) {
                sendErrorResponse(500);
                e.printStackTrace();
            }
        }

        private void sendErrorResponse(int code) {
            if (this.verbose)
                System.out.println(this.logHeader + "Sending Error response " + code);

            switch(code) {
                case 400:
                    this.out.print("HTTP/1.0 400 Bad Request\r\n" + this.headers);
                    break;
                case 403:
                    this.out.print("HTTP/1.0 403 Forbidden\r\n" + this.headers);
                    break;
                case 404:
                    this.out.print("HTTP/1.0 404 Not Found\r\n" + this.headers);
                    break;
                case 500:
                default:
                    this.out.print("HTTP/1.0 500 Internal Server Error\r\n" + this.headers);
                    break;
            }

            out.flush();
        }

        private void parseFullRequest() throws IOException{
            StringBuilder headers = new StringBuilder();
            String line = this.in.readLine();
            int contentLength = 0;

            if (!line.contains(GET) && !line.contains(POST))
                sendErrorResponse(400); // Bad request

            // First line is always the header
            this.request = line;

            System.out.println(this.logHeader + "REQUEST:\n\n-------------------------\n" + this.request);

            while (line != null) {

                if (line.contains(CONTENT_LENGTH))
                    contentLength = Integer.parseInt(line.substring(CONTENT_LENGTH.length()));

                line = in.readLine();
                headers.append(line).append("\r\n");

                // Break at end of headers.
                if (line.equals("")) break;
            }

            this.headers = headers.toString();
            System.out.print(this.headers);

            if (contentLength > 0) {
                int c;
                int count = 0;
                StringBuilder body = new StringBuilder();

                while ((c = in.read()) != -1) {
                    body.append((char)c);
                    count++;

                    if (count >= contentLength) break;
                }

                this.body = body.toString();

                System.out.println(this.body + "\n-------------------------\n");
            }
        }
    }