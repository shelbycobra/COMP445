import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Scanner;
import java.lang.Exception;


public class httpc {
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private boolean verbose = false;

    // HTTP request components
    private String host = "";
    private String query = "";
    private String page = "";
    private String additionalHeaders = "";
    private String body = ""; // POST only
    private String outputFilePath = "";
    private String response = "";

    /**
     * Prints help instructions to the console.
     */
    public void help(String[] args) {
        // Print out help get or help post
        if (args.length >= 2) {
            if (args[1].equals("get")) {
                System.out.println("\nusage: java HTTPCurl get [-v] [-h key:value] URL\n"
                    + "\nGet executes a HTTP GET request for a given URL.\n\n"
                    + "\t-v\tPrints the detail of the response such as protocol, status, and headers.\n"
                    + "\t-h\tkey:value Associates headers to HTTP Request with the format 'key:value'.\n"
                    + "\t-o\tfile Writes the response content to the file.\n"
                );
                System.exit(0);
            }
            if (args[1].equals("post")) {
                System.out.println("\nusage: httpc post [-v] [-h key:value] [-d inline-data] [-f file] URL\n"
                    + "\nPost executes a HTTP POST request for a given URL with inline data or from file.\n\n"
                    + "\t-v\tPrints the detail of the response such as protocol, status, and headers.\n"
                    + "\t-h\tkey:value Associates headers to HTTP Request with the format 'key:value'.\n"
                    + "\t-d\tstring Associates an inline data to the body HTTP POST request.\n"
                    + "\t-f\tfile Associates the content of a file to the body HTTP POST request.\n"
                    + "\t-o\tfile Writes the response content to the file.\n"
                    + "\nEither [-d] or [-f] can be used but not both.\n"
                );
                System.exit(0);
            }
            System.out.println("\n>>> Invalid command: " + args[1] + "\n");
        }

        // Print out help
        System.out.println("\nHTTPCurl is a curl-like application but supports HTTP protocol only.\n"
                + "\nUsage:\n"
                + "\tjava HTTPCurl command [arguments]\n"
                + "\nThe commands are:\n"
                + "\tget\texecutes a HTTP GET request and prints the response.\n"
                + "\tpost\texecutes a HTTP POST request and prints the response.\n"
                + "\thelp\tprints this screen.\n"
                + "\nUse \"java HTTPCurl help [command]\" for more information about a command.\n"
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
            } else if (arg.equals("-h")) {
                index++;
                StringBuilder headers = new StringBuilder();
                headers.append(args[index]).append("\r\n");
                this.additionalHeaders += args[index].toString();
            } else if (arg.equals("-d")) {
                index++;
                this.body = args[index];
                if(fOptionUsed) {
                    throw new Exception("-d and -f cannot be used simultaneously.");
                }
                dOptionUsed = true;
            } else if (arg.equals("-f")) {
                index++;
                Scanner reader = new Scanner(new File(args[index]));
                while(reader.hasNext()) {
                    this.body += reader.nextLine();
                }
                reader.close();
                if(dOptionUsed) {
                    throw new Exception("-d and -f cannot be used simultaneously.");
                }
                fOptionUsed = true;
            } else if (arg.equals("-o")) {
                index++;
                this.outputFilePath = args[index];
            } else {
                parseURL(args[index]);
            }

            if (index >= args.length)
                throw new Exception("No URL was provided.");

            index++;
        }
    }

    /**
     * Performs a HTTP GET/POST operation.
     * @param type                 Either "get" or "post"
     * @throws UnkownHostException If host cannot be found.
     * @throws IOException         If a problem occurs during IO on the TCP socket.
     * @throws Exception           If type is neither "get" nor "post".
     */
    public void sendRequest(String type) throws UnknownHostException, IOException, Exception{
        socket = new Socket(this.host, 80);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        String request = buildRequest(type);
        
        out.write(request.getBytes());
        out.flush();

        StringBuilder response = new StringBuilder();
        int data = in.read();
    
        while(data != -1 ) {
            response.append((char)data);
            data = in.read();
        }

        socket.close();

        this.response = response.append("\n\n").toString();
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
                System.out.println(this.response);
            else
                System.out.println(responseBody);
        }
    }

    /**
     * Checks if an HTTP response returns a redirection code.
     * @return A boolean indicating whether to perform a redirection.
     */
    public boolean requiresRedirection(){
        String httpStr = "HTTP/1.0 ";
        String locationStr = "Location: ";

        int indexOfCode = this.response.indexOf(httpStr) + httpStr.length();

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

    /**
     * Parses a URL into `host` (www.google.ca), `page` (/get) and `query` (?key=value) 
     * @param fullURL A URL to be parsed.
     */
    private void parseURL(String fullURL) {
        String http = "http://";
        int indexOfHTTP = fullURL.indexOf(http);

        // Remove http:// prefix
        if (indexOfHTTP != -1)
            fullURL = fullURL.substring(indexOfHTTP + http.length());

        // Find index of first / and ? to identify the page
        int indexOfQueury = fullURL.indexOf('?');
        int indexOfSlash = fullURL.indexOf('/');

        // If ? doesn't exist in URL, then no query exists in the URL and set indexOfQuery to length of URL
        if (indexOfQueury == -1)
            indexOfQueury = fullURL.length();

        int URLEndPoint;

        // Set instance variables
        if (indexOfSlash == -1) {
            // URL has no page, eg. "www.example.com" or "www.example.com?key=value"
            this.page = "/";
            URLEndPoint = indexOfQueury;
        } else {
            // URL has a page to navigate, eg. "www.example.com/path/to/path" or "www.example.com/path/to/path?key=value"
            this.page = fullURL.substring(indexOfSlash, indexOfQueury);
            URLEndPoint = indexOfSlash;
        }
        
        // Only set this.query if the new query is nonempty.
        String newQuery = fullURL.substring(indexOfQueury);
        if (!newQuery.equals(""))
            this.query = newQuery;

        // Only set this.host if the new host is nonempty.
        String newHost = fullURL.substring(0, URLEndPoint);
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

    /**
     * 
     */
    public static void main (String[] args) {
        HTTPCurl httpc = new HTTPCurl();

        if (args.length == 0 || (!args[0].equals("get") && !args[0].equals("post") && !args[0].equals("help"))) {
            System.out.println("\nERROR:  No get, post or help method was provided.");
            System.out.println("\tRun `java HTTPCurl help` for guidance.");
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
            System.out.println("\nERROR:  " + e);
            System.out.println("\tRun `java HTTPCurl help` for guidance.");
        }
    }
}