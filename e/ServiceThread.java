import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.io.*;
import java.net.*;
import java.util.*;

class ServiceThread extends Thread {

	static boolean _DEBUG = true;
	static int reqCount = 0;

	String WWW_ROOT;
	Socket connSocket;

	BufferedReader inFromClient;
	DataOutputStream outToClient;

	private static int cacheSize = 8096;

	private static Map<String, byte[]> cache = new HashMap<>();

	String urlName;
	String userAgent;
	String cookie;
	File fileInfo;
	String fileName;
	String lastModifiedString = null;
	String ifModifiedSinceString = null;

	private List<Socket> pool;

	public	ServiceThread(List<Socket> pool, String WWW_ROOT)
			throws Exception {

		this.pool = pool;

		this.WWW_ROOT = WWW_ROOT;

	}

	@Override
	public void run(){

		while (true) {
			Socket s = null;
			synchronized(pool) {
				while (pool.isEmpty()) {
					try {
						System.out.println("Thread " + this + " sees empty pool.");
						pool.wait();
					} catch (InterruptedException e) {
						System.out.println("Waiting for pool interrupted.");
					}
				}
				s = (Socket) pool.remove(0);
				System.out.println("Thread " + this 
								   + " process request " + s);
			}

			this.reqCount++;
			try {
				this.connSocket = s;
				this.inFromClient = new BufferedReader(new InputStreamReader(this.connSocket.getInputStream()));
				this.outToClient = new DataOutputStream(this.connSocket.getOutputStream());
				processRequest();
			} catch (Exception  e) {
				e.printStackTrace();
			} 
		}
	}

	void processRequest() throws IOException {

		boolean b1 = loadRequestHeader();

		if (!b1) {
			outputError(500, "Bad request");
			return;
		}

		int status = mapUrl2File();

		try {
			switch (status) {
			case 501: {
				outputError(500, "Bad request");
				break;
			}
			case 404: {
				outputError(404, "Not Found");
				sendFile();
				break;
			}
			case 304: {
				try {
					outToClient.writeBytes("HTTP/1.0 304" + "Not Modified \r\n");
					outToClient.writeBytes("Last-Modified: " + lastModifiedString + "\r\n");
				} catch (Exception e) {}
				break;
			}
			case 200: {
				if (isExecutable(fileInfo)) {
					DEBUG("Executing...");
					runCGIProgram();
				} else {
					sendResponse();
				}
			}

			default:
				break;
			}
		} catch (Exception e) {
			outputError(400, "Server error");
		}
	}

	private boolean loadRequestHeader() throws IOException {
		String requestMessageLine = inFromClient.readLine();

		DEBUG("Request " + reqCount + ": " + requestMessageLine);

		String[] request = requestMessageLine.split("\\s");

		if (request.length < 2 || !request[0].equals("GET")) {
			DEBUG("Invalid request");
			return false;
		}

		urlName = request[1];

		String line = inFromClient.readLine();
		while (!line.equals("")) {
			if (line.startsWith("User-Agent:")) {
				userAgent = line.substring(12);
			} else if (line.startsWith("Cookie:")) {
				cookie = line.substring(8);
			} else if (line.startsWith("If-Modified-Since:")) {
				ifModifiedSinceString = line.substring(19);
			}
			DEBUG("Header: " + line);
			line = inFromClient.readLine();
		}

		return true;
	}

	private int mapUrl2File() throws IOException {
		if (urlName.equals("/")) {
			urlName = "index.html";
			if (userAgent.contains("Mobile") || userAgent.contains("Android") || userAgent.contains("Iphone")) {
				urlName = "m_index.html";
			}
		}

		Path reqPath = Paths.get(WWW_ROOT, urlName);
		Path absolutePath = reqPath.toAbsolutePath().normalize();

		if (!absolutePath.startsWith(WWW_ROOT)) {
			DEBUG("501 Error detected!");
			return 501;
		}

		fileName = absolutePath.toString();
		fileInfo = new File(fileName);
		if (!fileInfo.isFile()) {
			fileName = WWW_ROOT + "404.html";
			if (userAgent.contains("Mobile") || userAgent.contains("Android") || userAgent.contains("Iphone")) {
				fileName = WWW_ROOT + "m_404.html";
			}
			fileInfo = new File(fileName);
			DEBUG("404 NOT FOUND");
			return 404;
		}

		DEBUG("Map to File name: " + fileName);

		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy M dd HH:mm:ss");
		Date lastModified = new Date(Files.getLastModifiedTime(Paths.get(fileName)).toMillis());
		lastModifiedString = dateFormat.format(lastModified);
		if (ifModifiedSinceString != null) {
			if (ifModifiedSinceString.compareTo(lastModifiedString) >= 0) {
				DEBUG("304 Not modified");
				return 304;
			}
		}

		DEBUG("200 OK");

		return 200;
	}

	void outputError(int errCode, String errMsg) {
		try {
			outToClient.writeBytes("HTTP/1.0 " + errCode + " " + errMsg + "\r\n");
		} catch (Exception e) {
		}
	}

	private void runCGIProgram() throws IOException {
		// environment variables
		Map<String, String> envVariablesMap = new HashMap<String, String>();
		envVariablesMap.put("CGI", fileName);
		envVariablesMap.put("REQUEST_METHOD", "GET");

		ProcessBuilder processBuilder = new ProcessBuilder(fileName);

		processBuilder.environment().putAll(envVariablesMap);

		Process process = processBuilder.start();

		try (InputStream inputStream = process.getInputStream()) {
			byte[] output = inputStream.readAllBytes();

			outToClient.writeBytes("HTTP/1.0 200 Document Follows\r\n");
			outToClient.writeBytes("Set-Cookie: MyCool433Seq12345\r\n");

			outToClient.writeBytes("Content-Type: text/plain\r\n");

			outToClient.writeBytes("Last-Modified: " + lastModifiedString + "\r\n");

			outToClient.write(output);
		}
	}

	private boolean isExecutable(File file) {
		return file.canExecute();
	}

	private void sendResponse() throws IOException {

		outToClient.writeBytes("HTTP/1.0 200 Document Follows\r\n");
		outToClient.writeBytes("Set-Cookie: MyCool433Seq12345\r\n");

		if (urlName.endsWith(".jpg"))
			outToClient.writeBytes("Content-Type: image/jpeg\r\n");
		else if (urlName.endsWith(".gif"))
			outToClient.writeBytes("Content-Type: image/gif\r\n");
		else if (urlName.endsWith(".html") || urlName.endsWith(".htm"))
			outToClient.writeBytes("Content-Type: text/html\r\n");
		else
			outToClient.writeBytes("Content-Type: text/plain\r\n");

		outToClient.writeBytes("Last-Modified: " + lastModifiedString + "\r\n");

		try {
			sendFile();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}

	private void sendFile() throws Exception {

		byte[] fileInBytes = getFile(fileName);

		outToClient.writeBytes("Content-Length: " + fileInBytes.length + "\r\n");
		outToClient.writeBytes("\r\n");

		outToClient.write(fileInBytes, 0, fileInBytes.length);
	}

	private static byte[] getFile(String fileName) {
        // Check if the file is in the cache
        if (cache.containsKey(fileName)) {
            System.out.println("Cache hit for " + fileName);
            return cache.get(fileName);
        }

        // Read the file from disk
        System.out.println("Cache miss for " + fileName);
        try (InputStream fileInputStream = new FileInputStream(fileName)) {
            byte[] fileContent = fileInputStream.readAllBytes();

            // Add the file to the cache if there is enough space
            if (calculateCacheSize() + fileContent.length <= cacheSize * 1024) {
                cache.put(fileName, fileContent);
            }

            return fileContent;

        } catch (IOException e) {
            e.printStackTrace();
            return new byte[0]; // Return an empty byte array in case of an error
        }
    }

    private static int calculateCacheSize() {
        int totalSize = 0;
        for (byte[] content : cache.values()) {
            totalSize += content.length;
        }
        return totalSize;
    }


	static void DEBUG(String s) {
		if (_DEBUG)
			System.out.println(s);
	}

}