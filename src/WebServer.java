
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * @author James MacIsaac
 *
 */
public class WebServer extends Thread {

    private int port;
    private ServerSocket listener;

    private ExecutorService es;

    private volatile boolean shutdown = false;

    public WebServer(int port) {
        try {
            if (port <= 1024 || port >= 65536) {
                throw new Exception("port number invalid");
            }
            this.port = port;
            listener = new ServerSocket(port);
            es = Executors.newFixedThreadPool(8);
        } catch (Exception e) {
            System.exit(-1);
        }
    }

    //run method for the server thread
    @Override
    public void run() {
        try {
            // set socket timeout option using setSoTimeout(1000)
            listener.setSoTimeout(1000);

            while (!shutdown) {
                try {
                    Socket s = listener.accept();
                    Worker w = new Worker(s);
                    es.execute(w);
                    System.out.println("Forked a new worker thread");
                    // forks a new thread to serve the request
                } catch (SocketTimeoutException ste) {
                    // this is ok
                }
            } // while

            esShutdown(); // shuts down the ExecutorService

        } catch (IOException io) {
            esShutdown();
        }
    }

    // class which handles a single request thread's logic
    private class Worker implements Runnable {

        private final Socket socket;

        private OutputStream outputStream;
        private Scanner inputStream;

        //regexes to validate the request lines
        String htRegex = "[A-Z]+ (((\\/[a-zA-Z0-9_\\-~=+]+)+.[a-z]+)|(\\/)) [A-Z0-9\\/.]+";
        Pattern htPatt = Pattern.compile(htRegex);
        String hlRegex = "([A-Z]\\w+(-[A-Z]\\w+)*)+: [A-Za-z-/;:,.\\(\\)=~_ \\*\\+0-9]+";
        Pattern hlPatt = Pattern.compile(hlRegex);

        Worker(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {

            Request request;
            Response response;
            System.out.println("Thread [" + Thread.currentThread().getName() + "] execution on port [" + socket.getPort() + "]");
            try {
                outputStream = socket.getOutputStream();
                inputStream = new Scanner(new InputStreamReader(socket.getInputStream()));

                String line;
                try {
                    //read first line of request
                    line = inputStream.nextLine();
                } catch (Exception e) {
                    throw new IOException();
                }
                // check if first line of request is invalid
                if (!htPatt.matcher(line).matches()) {
                    throw new RequestHeaderLineException("Request Header First Line Invalid - Does not match <[A-Z]+ (((\\/[a-z0-9_\\-~=+]+)+.[a-z]+)|(\\/)) [A-Z0-9\\/.]+>");
                }
                String firstLine[] = line.split(" ");
                request = new Request(firstLine[0], firstLine[1], firstLine[2]);
                int count = 1;
                while (!(line = inputStream.nextLine()).equals("")) { // end of request
                    if ((count == 1) && (line.indexOf(':') != line.lastIndexOf(':'))) {
                        line = line.substring(0, line.lastIndexOf(':'));
                    }
                    if (!hlPatt.matcher(line).matches()) {
                        throw new RequestHeaderLineException("Request Header Line Invalid - Does not match <([A-Z]\\w+(-[A-Z]\\w+)*)+: [A-Za-z-/;:,.\\(\\)=~_ \\*\\+0-9]+");
                    }
                    String keyValue[] = new String[2];
                    keyValue[0] = line.substring(0, line.indexOf(':'));
                    keyValue[1] = line.substring(line.indexOf(' ') + 1);
                    request.addHeader(keyValue[0], keyValue[1]);
                    count++;
                }

                //Request header printed on server side
                System.out.println("\nRequest Header:");
                System.out.println("************************************");
                System.out.println(request.getType() + " " + request.getPath() + " " + request.getProtocol());
                Set s = request.getHeaders();
                Iterator i = s.iterator();
                while (i.hasNext()) {
                    Map.Entry me = (Map.Entry) i.next();
                    String l = me.getKey() + ": " + me.getValue(); // forcing the String type
                    System.out.println(l);
                }
                System.out.println("************************************");
                System.out.println();

                //check if file is present
                if (!fileExistsAndReadable(request.getPath())) { // Status Code becomes 404 - NOT FOUND
                    System.out.println("File not Found");
                    response = new Response(request.getProtocol(), 404);
                    response.setContent("404 NOT FOUND\n".getBytes());
                    response.addResponseHeaders("JamesServ/4.5k", null, null);
                    // transmit the header
                    transmitHeader(outputStream, response);
                    transmitFile(outputStream, response);
                } else { // Status Code becomes 200 - OK
                    response = new Response(request.getProtocol(), 200);
                    byte b[] = fileContents(request.getPath());
                    response.setContent(b);
                    String timestamp = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz").format(Calendar.getInstance().getTime());
                    response.addResponseHeaders("JamesServ/4.5k", timestamp, b.length + "");
                    // transmit the header
                    transmitHeader(outputStream, response);
                    // transmit file after header
                    transmitFile(outputStream, response);
                }
            } catch (RequestHeaderLineException hle) { // Status Code becomes 400 - BAD REQUEST
                response = new Response("HTTP/1.1", 400);
                String timestamp = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz").format(Calendar.getInstance().getTime());
                response.setContent("400 BAD REQUEST\n".getBytes());
                response.addResponseHeaders("JamesServ/4.5k", timestamp, null);
                //return a response which states that it was a bad request
                transmitHeader(outputStream, response);
                transmitFile(outputStream, response);
            } catch (FileException fe) {
                System.out.println(fe.getMessage());
            } catch (IOException io) {
                System.out.println("Connection interrupted");
            } finally {
                try { // flush and close the connection
                    outputStream.flush();
                    inputStream.close();
                    outputStream.close();
                    System.out.println("Thread [" + Thread.currentThread().getName() + "] finished on port [" + socket.getPort() + "]\n");
                } catch (IOException io) {
                }
            }
        }

        /**
         * @author James MacIsaac
         * @desc Class to store and organize the request header data
         */
        private class Request {

            private final String type;
            private final String path;
            private final String protocol;
            // LinkedHashMap doubly links the buckets to allow for insertion order lookups
            private LinkedHashMap headers;

            Request(String type, String path, String protocol) {
                this.type = type;
                this.path = path;
                this.protocol = protocol;
                headers = new LinkedHashMap<>();
            }

            public Set getHeaders() {
                return headers.entrySet();
            }

            /**
             * @author James MacIsaac
             * @param tag
             * @param value
             * @desc adds a header line to the request header LinkedHashMap
             */
            public void addHeader(String tag, String value) {
                headers.put(tag, value);
            }

            public String getType() {
                return this.type;
            }

            public String getPath() {
                return this.path;
            }

            public String getProtocol() {
                return this.protocol;
            }
        }

        /**
         * @author James MacIsaac
         * @desc Class to store and organize the Response header and content
         * data
         */
        private class Response {

            private final String protocol;
            private final int status;
            private LinkedHashMap headers;
            private byte[] content;

            public Response(String protocol, int status) {
                this.protocol = protocol;
                this.status = status;
                headers = new LinkedHashMap<>();
                content = null;
            }

            public String getProtocol() {
                return this.protocol;
            }

            /**
             * @author James MacIsaac
             * @return String
             * @desc returns a formatted string to represent the status code
             */
            public String getStatus() {
                switch (this.status) {
                    case 200: // OK
                        return this.status + " OK";
                    case 304: // NOT MODIFIED
                        return this.status + " NOT MODIFIED";
                    case 400: // BAD REQUEST
                        return this.status + " BAD REQUEST";
                    case 404: // NOT FOUND
                        return this.status + " NOT FOUND";
                    default: // Unrecognized status code
                        return "400" + " BAD REQUEST";
                }
            }

            private void addHeader(String key, String Value) {
                headers.put(key, Value);
            }

            public Set getHeaders() {
                return headers.entrySet();
            }

            public byte[] getContent() {
                return content;
            }

            public void setContent(byte[] content) {
                this.content = content;
            }

            /**
             * @author James MacIsaac
             * @param server
             * @param lm
             * @param cl
             * @desc Adds a set of useful response headers to the response based
             * on the status code
             */
            public void addResponseHeaders(String server, String lm, String cl) {
                String timestamp = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz").format(Calendar.getInstance().getTime());
                addHeader("Date", timestamp); // added Date header with current timestamp
                addHeader("Server", server);
                if (this.status == 200) { // only add these parts to the header if 200 OK
                    addHeader("Last-Modified", lm);
                } else {
                    addHeader("Content-Type", "text/html");
                }
                addHeader("Content-Length", cl);
                addHeader("Connection", "close");
            }
        }

        private class RequestHeaderLineException extends Exception {

            public RequestHeaderLineException(String message) {
                super(message);
            }

        }

        /**
         * @author James MacIsaac
         * @param path
         * @return boolean
         * @throws WebServer.Worker.FileException
         * @desc method that checks for the existence of a file at a given path
         * and whether or not the file is actually readable
         */
        private boolean fileExistsAndReadable(String path) throws FileException {
            File file = new File(System.getProperty("user.dir") + path);
            if (file.exists()) {
                return file.canRead();
            } else {
                return false;
            }
        }

        /**
         * @author James MacIsaac
         * @param path
         * @return byte[]
         * @desc method that fetches the file contents of a file at a given path
         * This method is not very safe for arbitrary file path look ups error
         * checking is done in the fileExistsAndREadable method and this is
         * called after if all is ok
         */
        private byte[] fileContents(String path) {
            try {
                File file = new File(System.getProperty("user.dir") + path);
                Path p = file.toPath();
                return Files.readAllBytes(p);
            } catch (IOException e) {
                System.out.println("Unable to get file contents");
                return null;
            }
        }

        private class FileException extends Exception {

            public FileException(String message) {
                super(message);
            }

        }

        /**
         * @author James MacIsaac
         * @param oStream
         * @param response
         * @desc method that sends the header of a response object to the client
         */
        private void transmitHeader(OutputStream oStream, Response response) {
            try {
                Set s = response.getHeaders();
                Iterator i = s.iterator();
                String fullHeader = "";
                fullHeader += response.getProtocol() + " " + response.getStatus() + "\r\n"; // first line
                while (i.hasNext()) {
                    Map.Entry me = (Map.Entry) i.next();
                    String l = me.getKey() + ": " + me.getValue() + "\r\n";
                    fullHeader += l;
                }
                fullHeader += "\r\n";
                oStream.write(fullHeader.getBytes("UTF-8"));
                System.out.println("Response Header:");
                System.out.println("************************************");
                System.out.println(fullHeader.substring(0, fullHeader.lastIndexOf("\r\n") - 2));
                System.out.println("************************************");
            } catch (IOException io) {
                System.out.println("Problem writing header");
            }
        }

        /**
         * @author James MacIsaac
         * @param oStream
         * @param response 
         * @desc method to transmit the file data for the file requested 
         * by the client only gets called if the Status code is 200 OK
         */
        private void transmitFile(OutputStream oStream, Response response) {
            try {
                oStream.write(response.getContent());
            } catch (Exception e) {
                System.out.println("Interruption while writing the file contents\n");
            }
        }

    }

    public void shutdown() {
        shutdown = true;
    }

    /**
     * @author James MacIsaac
     * @desc method to gracefully shutdown the ExecutorService
     * send the shutdown signal to stop the spawning of new threads. If 
     * after a reasonable delay there are still threads executing in the
     * Service, they are forcefully shutdown and the service is closed.
     */
    private void esShutdown() {
        try {
            // do not accept any new tasks
            es.shutdown();

            // wait 5 seconds for existing tasks to terminate
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                es.shutdownNow(); // cancel currently executing tasks
            }

        } catch (InterruptedException e) {
            // cancel currently executing tasks
            es.shutdownNow();
        }
    }

}
