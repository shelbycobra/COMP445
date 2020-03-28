import java.io.IOException;
import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;

import java.util.ArrayList;
import java.util.Scanner;

import java.lang.Exception;

import java.net.UnknownHostException;
import java.net.InetSocketAddress;

import tcp.TCPSocket;

public class httpc {
    private final static String CONTENT_LENGTH = "Content-Length: ";

    private TCPSocket socket;
    private boolean verbose = false;
    private boolean veryVerbose = false;

    // HTTP request components
    private String host = "";
    private int port = 80;
    private String query = "";
    private String page = "";
    private String additionalHeaders = "";
    private String body = ""; // POST only
    private String outputFilePath = "";
    private String response = "";

    private String routerAddr;
    private int routerPort;

    /**
     *
     */
    public static void main (String[] args) {
        httpc httpc = new httpc();

        if (args.length == 0 || (!args[0].equals("get") && !args[0].equals("post") && !args[0].equals("help"))) {
            System.out.println("\nERROR:  No get, post or help method was provided.");
            System.out.println("\tRun `java httpc help` for guidance.");
            System.exit(1);
        }

        if (args[0].equals("help"))
            httpc.help(args);

        try {
            httpc.parseArguments(args);

            while(true) {
                httpc.sendRequest(args[0]);
                httpc.handleResponse();

                if (!httpc.requiresRedirection())
                    break;
            }

        } catch(UnknownHostException e) {
            System.out.println(e);
        } catch(IOException e) {
            System.out.println(e);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("\n>>>> Run `java httpc help` for guidance.");
        }
    }

    /**
     * Default constructor
     */
    public httpc() {}

    /**
     * Prints help instructions to the console.
     */
    public void help(String[] args) {
        // Print out help get or help post
        if (args.length >= 2) {
            if (args[1].equals("get")) {
                System.out.println("\nusage: java httpc get [-v] [-h key:value] URL\n"
                    + "\nGet executes a HTTP GET request for a given URL.\n\n"
                    + "\t-v\tPrints the detail of the response such as protocol, status, and headers.\n"
                    + "\t-vv\tPrints the detail of the TCP socket as well as what is printed by '-v'.\n"
                    + "\t-h\tkey:value Associates headers to HTTP Request with the format 'key:value'.\n"
                    + "\t-o\tfile Writes the response content to the file.\n"
                    + "\t-r\tFull router address.\n"
                );
                System.exit(0);
            }
            if (args[1].equals("post")) {
                System.out.println("\nusage: httpc post [-v] [-h key:value] [-d inline-data] [-f file] URL\n"
                    + "\nPost executes a HTTP POST request for a given URL with inline data or from file.\n\n"
                    + "\t-v\tPrints the detail of the response such as protocol, status, and headers.\n"
                    + "\t-vv\tPrints the detail of the TCP socket as well as what is printed by '-v'.\n"
                    + "\t-h\tkey:value Associates headers to HTTP Request with the format 'key:value'.\n"
                    + "\t-d\tstring Associates an inline data to the body HTTP POST request.\n"
                    + "\t-f\tfile Associates the content of a file to the body HTTP POST request.\n"
                    + "\t-o\tfile Writes the response content to the file.\n"
                    + "\t-r\tFull router address.\n"
                    + "\nEither [-d] or [-f] can be used but not both.\n"
                );
                System.exit(0);
            }
            System.out.println("\n>>> Invalid command: " + args[1] + "\n");
        }

        // Print out help
        System.out.println("\nhttpc is a curl-like application but supports HTTP protocol only.\n"
                + "\nUsage:\n"
                + "\tjava httpc command [arguments]\n"
                + "\nThe commands are:\n"
                + "\tget\texecutes a HTTP GET request and prints the response.\n"
                + "\tpost\texecutes a HTTP POST request and prints the response.\n"
                + "\thelp\tprints this screen.\n"
                + "\nUse \"java httpc help [command]\" for more information about a command.\n"
            );
        System.exit(0);
    }

    /**
     * Parses arguments based on the options passed via the command line.
     *
     * @throws Exception If -d or -f are used together or a URL was never passed.
     * @param args       An array of arguments.
     */
    public void parseArguments(String[] args) throws Exception {
        boolean dOptionUsed = false;
        boolean fOptionUsed = false;

        int index = 1;

        while (index < args.length) {

            String arg = args[index];

            if (arg.equals("-v")){
                this.verbose = true;
            } else if (arg.equals("-vv")) {
                this.veryVerbose = true;
                this.verbose = true;
            } else if (arg.equals("-h")) {
                index++;
                StringBuilder headers = new StringBuilder();
                String header = args[index];
                int indexOfColon = header.indexOf(":");
                headers.append(header.substring(0, indexOfColon+1))
                    .append(" ").append(header.substring(indexOfColon+1))
                    .append("\r\n");
                this.additionalHeaders += headers.toString();
            } else if (arg.equals("-d")) {
                StringBuilder input = new StringBuilder();
                while(true) {
                    index++;
                    String word = args[index];

                    System.out.println("in here : " + word);

                    if (word.matches("^-[a-z][a-z]?$") // Matches an option
                    || word.matches("(http:\\/\\/)?[\\w\\d]+\\.[\\w\\d\\.]+[:?\\d]?[\\w\\d.\\/]+\\/?\\??[\\w\\d=&]*") // Matches a URL
                    || word.matches("localhost:[0-9]+.*")) // Matches a localhost url
                    {
                        index--;
                        break;
                    }
                    input.append(word).append(" ");

                    if (index >= args.length-1) break;
                }

                this.body = input.toString();
                if(fOptionUsed) {
                    throw new Exception("-d and -f cannot be used simultaneously.");
                }
                dOptionUsed = true;
            } else if (arg.equals("-f")) {
                index++;
                Scanner reader = new Scanner(new File(args[index]));
                while(reader.hasNext()) {
                    this.body += reader.nextLine().trim().concat("\r\n");
                }
                reader.close();
                if(dOptionUsed) {
                    throw new Exception("-d and -f cannot be used simultaneously.");
                }
                fOptionUsed = true;
            } else if (arg.equals("-o")) {
                index++;
                this.outputFilePath = args[index];
            } else if (arg.equals("-r")) {
                index++;
                int indexOfColon = args[index].indexOf(':');
                this.routerAddr = args[index].substring(0, indexOfColon);
                this.routerPort = Integer.parseInt(args[index].substring(indexOfColon+1));
            } else {
                parseURL(args[index]);
            }

            if (index >= args.length)
                throw new Exception("No URL was provided.");

            index++;
        }

        if (this.routerAddr == null || this.routerPort == 0)
            throw new Exception("No router address was provided.");
    }

    /**
     * Performs a HTTP GET/POST operation.
     * @param type                 Either "get" or "post"
     * @throws UnkownHostException If host cannot be found.
     * @throws IOException         If a problem occurs during IO on the TCP socket.
     * @throws Exception           If type is neither "get" nor "post".
     */
    public void sendRequest(String type) throws UnknownHostException, IOException, Exception{
        socket = new TCPSocket(new InetSocketAddress(this.host, this.port), new InetSocketAddress(this.routerAddr, this.routerPort), this.veryVerbose);

        String request = buildRequest(type);
        System.out.println(request);

        // Write request to server
        socket.write(request.getBytes());

        // Wait for and build a response
        buildResponse();

        socket.close();
    }

     /**
     * @param type Either "get" or "post".
     * @throws Exception If type is neither "get" or "post".
     * @return request string
     */
    private String buildRequest(String type) throws Exception {
        if (type.equals("get")) {
            return "GET " + this.page + this.query + " HTTP/1.0\r\n"
                    + this.additionalHeaders
                    + "\r\n";
        } else if (type.equals("post")) {
            return "POST " + this.page + this.query + " HTTP/1.0\r\n"
                    + this.additionalHeaders
                    + "Content-Length: " + this.body.length() + "\r\n"
                    + "\r\n"
                    + this.body;
        } else {
            throw new Exception("Invalid method: " + type);
        }
    }

    private void buildResponse() throws IOException {
        int contentLength = 0;
        StringBuilder headers = new StringBuilder();
        StringBuilder body = new StringBuilder();
        String response = this.socket.read();
        String[] lines = response.trim().split("\n");

        // System.out.println("**** A BUILDING RESPONSE **** ");
        boolean isHeader = true;
        int pos = 0;
        while (pos < lines.length) {
            String line = lines[pos].trim();
            pos++;

            if (isHeader)
                headers.append(line).append("\r\n");
            else
                body.append(line).append("\r\n");

            if (line.contains(CONTENT_LENGTH))
                contentLength = Integer.parseInt(line.substring(CONTENT_LENGTH.length()).trim());

            if (line.equals(""))
                isHeader = false;
        }

        // System.out.println("**** B BUILDING RESPONSE **** " + headers + " \n--------\n" + body);

        if (body.length() < contentLength - 2 && body.length() > 0) {

            // remove the appended '\r\n' at the end of the body.
            body.deleteCharAt(body.length() - 1);
            body.deleteCharAt(body.length() - 1);

            while (body.length() < contentLength - 2) {
                // System.out.println("**** C BUILDING RESPONSE **** " + body.length() + " and " + (contentLength - 2));
                body.append(socket.read());
            }
        }

        this.response = headers.append(body.toString()).toString();
    }


    /**
     * Handles the response by parsing the body from the header and either prints out the response or writes it to a file
     * given by the -o option.
     * @throws IOException
     */
    public void handleResponse() throws IOException {
        String responseBody = getResponseBody();

        if (!this.outputFilePath.equals("")) {
            BufferedWriter writer = new BufferedWriter(new FileWriter(this.outputFilePath, true));
            if (this.verbose)
                writer.append(this.response);
            else
                writer.append(responseBody);
            writer.close();
        } else {
            if (this.verbose)
                System.out.print(this.response);
            else
                System.out.print(responseBody);
        }
    }

    /**
     * Checks if an HTTP response returns a redirection code.
     * @return A boolean indicating whether to perform a redirection.
     */
    public boolean requiresRedirection() throws Exception{
        String httpStr = "HTTP/1.0 ";
        String locationStr = "Location: ";

        int indexOfCode = this.response.indexOf(httpStr) + httpStr.length();

        if (indexOfCode == 8 || this.response.length() < 3)
            return false;

        if (this.response.charAt(indexOfCode) == '3') {
            int indexOfLocation = this.response.indexOf(locationStr) + locationStr.length();
            String responseSubstr = this.response.substring(indexOfLocation);

            int indexOfFirstNewLine = responseSubstr.indexOf("\r");
            String newLocation = responseSubstr.substring(0, indexOfFirstNewLine);

            parseURL(newLocation);

            return true;
        }

        return false;
    }

    /**
     * Parses a URL into `host` (www.google.ca), `page` (/get) and `query` (?key=value)
     * @param fullURL A URL to be parsed.
     */
    private void parseURL(String fullURL) throws Exception {
        String URL;
        String http = "http://";
        int indexOfHTTP = fullURL.indexOf(http);

        // http:// can either be at the beginning of the URL or not there at all
        if (indexOfHTTP != 0 && indexOfHTTP != -1 ) {
            throw new Exception("Invalid URL: " + fullURL);
        }

        // Remove http:// prefix
        if (indexOfHTTP != -1)
            URL = fullURL.substring(indexOfHTTP + http.length());
        else
            URL = fullURL;

        // Find index of first / and ? to identify the page
        int indexOfQuery = URL.indexOf('?');
        int indexOfSlash = URL.indexOf('/');
        int indexOfPort  = URL.indexOf(':');

        // If ? doesn't exist in URL, then no query exists in the URL and set indexOfQuery to length of URL
        if (indexOfQuery == -1)
            indexOfQuery = URL.length();

        int URLEndPoint;

        // Set instance variables
        if (indexOfSlash == -1) {
            // URL has no page, eg. "www.example.com" or "www.example.com?key=value"
            this.page = "/";
            URLEndPoint = indexOfQuery;
        } else {
            // URL has a page to navigate, eg. "www.example.com/path/to/path" or "www.example.com/path/to/path?key=value"
            this.page = URL.substring(indexOfSlash, indexOfQuery);
            URLEndPoint = indexOfSlash;
        }

        // Only set this.query if the new query is nonempty.
        String newQuery = URL.substring(indexOfQuery);
        if (!newQuery.equals(""))
            this.query = newQuery;

        String newHost;

        if (indexOfPort != -1) {
            this.port = Integer.parseInt(URL.substring(indexOfPort + 1, URLEndPoint));
            newHost = URL.substring(0, indexOfPort);
        } else {
            // Only set this.host if the new host is nonempty.
            newHost = URL.substring(0, URLEndPoint);
        }
        if (!newHost.equals(""))
            this.host = newHost;
    }

    /**
     * Parses the response and returns the body.
     * @return body The body of the response.
     */
    private String getResponseBody() {
        StringBuilder body = new StringBuilder();
        String[] responseArray = this.response.split("\n");
        boolean buildResponseBody = false;

        for (int i = 0; i < responseArray.length; i++) {
            if (buildResponseBody)
                body.append(responseArray[i]).append("\n");
            if (responseArray[i].equals("\r")) // Body starts after an empty line of just \r.
                buildResponseBody = true;
        }

        return body.toString();
    }
}