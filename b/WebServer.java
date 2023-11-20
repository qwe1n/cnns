import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class WebServer{

    public static int serverPort = 6789;    
    //public static String WWW_ROOT = "/home/httpd/html/zoo/classes/cs433/";
    public static String WWW_ROOT = "/root/WWW/";

	public static int HEARTBEAT_PORT = 4578;

    public static void main(String args[]) throws Exception  {
	
			for (int i=0; i < args.length; i++ ) {
					if (args[i].equals("-config")) {
						i++;
						parseHttpdConf(args[i]);
					}
			}

			// create server socket
			ServerSocket welcomeSocket = new ServerSocket(serverPort);
			System.out.println("server listening at: " + welcomeSocket);
			System.out.println("server www root: " + WWW_ROOT);

			startHeartbeatMonitor();

			while (true) {

				try {

					// take a ready connection from the accepted queue
					Socket connectionSocket = welcomeSocket.accept();
					System.out.println("\nReceive request from " + connectionSocket);
			
					// process a request
					WebRequestHandler wrh = 
						new WebRequestHandler( connectionSocket, WWW_ROOT );

					new Thread(wrh).start();

				} catch (Exception e)
				{
					
				}
			} // end of while (true)
	
    } // end of main

	private static void parseHttpdConf(String configFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Listen")) {
                    serverPort = parsePort(line);
                } else if (line.contains("DocumentRoot")) {
                    WWW_ROOT = parseDocumentRoot(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static int parsePort(String line) {
        Pattern pattern = Pattern.compile("\\bListen\\s+(\\d+)\\b");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }

    private static String parseDocumentRoot(String line) {
        Pattern pattern = Pattern.compile("\\bDocumentRoot\\s+(\\S+)\\b");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

	private static void startHeartbeatMonitor() throws InterruptedException{
		new Thread(() -> {
				try (ServerSocket heartbeatServerSocket = new ServerSocket(HEARTBEAT_PORT)) {
					System.out.println("Heartbeat server started on port " + HEARTBEAT_PORT);

					while (true) {
						Socket heartbeatClientSocket = heartbeatServerSocket.accept();
						handleHeartbeatRequest(heartbeatClientSocket);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}).start();
	}

	private static void handleHeartbeatRequest(Socket heartbeatClientSocket) throws IOException { {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(heartbeatClientSocket.getInputStream()));
		     BufferedWriter out = new BufferedWriter(new OutputStreamWriter(heartbeatClientSocket.getOutputStream()))) {
			String requestLine = in.readLine();

			if (requestLine.equals("GET /heartbeat HTTP/1.0")) {
				String response = "HTTP/1.0 200 OK\r\n";
				response += "Content-Type: text/plain\r\n";
                response += "\r\n";
                response += "Heartbeat response: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\r\n";

                out.write(response);
                out.flush();
			}
		} catch (IOException e) {
            e.printStackTrace();
        }
	    }
	}
} // end of class WebServer
