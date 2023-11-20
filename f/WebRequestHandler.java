import java.text.SimpleDateFormat;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;



class WebRequestHandler {

	static boolean _DEBUG = true;
	static int reqCount = 0;


	SocketChannel socketChannel;

	private static int cacheSize = 8096;

	private static Map<String, byte[]> cache = new HashMap<>();

	String WWW_ROOT;
	SelectionKey key;

	BufferedReader inFromClient;

	String urlName;
	String userAgent;
	String cookie;
	File fileInfo;
	String fileName;
	String lastModifiedString = null;
	String ifModifiedSinceString = null;

	public WebRequestHandler(SelectionKey key, String WWW_ROOT)
			throws Exception {

		reqCount++;
		this.WWW_ROOT = WWW_ROOT;

		this.key = key;
	}

	void processRequest() throws IOException {

		socketChannel = (SocketChannel) key.channel();

		ByteBuffer buffer = ByteBuffer.allocate(1024);

		int bytesRead = socketChannel.read(buffer);

		if (bytesRead != -1) {
			buffer.flip();
			String request = StandardCharsets.UTF_8.decode(buffer).toString();
			inFromClient = new BufferedReader(new StringReader(request));
		} else {
			socketChannel.close();
			return;
		}

		boolean b1 = loadRequestHeader();

		if (!b1) {
			outputError(500, "Bad request");
			socketChannel.close();
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
					sdr("HTTP/1.0 304" + "Not Modified \r\n");
					sdr("Last-Modified: " + lastModifiedString + "\r\n");
					break;
				} catch (Exception e) {}
			}
			case 200: {
				if (isExecutable(fileInfo)) {
					DEBUG("executing");
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
			e.printStackTrace();
		}
		socketChannel.close();
	}

	private boolean loadRequestHeader() throws IOException {
		String requestMessageLine = inFromClient.readLine();

		DEBUG("Request " + reqCount + ": " + requestMessageLine);

		String[] request = requestMessageLine.split("\\s");

		if (request.length < 2 || !request[0].equals("GET")) {
			DEBUG("500 Invalid request");
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
			String response = "HTTP/1.0 " + errCode + " " + errMsg + "\r\n";
            sdr(response);
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

			sdr("HTTP/1.0 200 Document Follows\r\n");
			sdr("Set-Cookie: MyCool433Seq12345\r\n");

			sdr("Content-Type: text/plain\r\n");

			sdr("Last-Modified: " + lastModifiedString + "\r\n");

			socketChannel.write(ByteBuffer.wrap(output));
		}
	}

	private boolean isExecutable(File file) {
		return file.canExecute();
	}

	private void sendResponse() throws IOException {

		sdr("HTTP/1.0 200 Document Follows\r\n");
		sdr("Set-Cookie: MyCool433Seq12345\r\n");

		if (urlName.endsWith(".jpg"))
			sdr("Content-Type: image/jpeg\r\n");
		else if (urlName.endsWith(".gif"))
			sdr("Content-Type: image/gif\r\n");
		else if (urlName.endsWith(".html") || urlName.endsWith(".htm"))
			sdr("Content-Type: text/html\r\n");
		else
			sdr("Content-Type: text/plain\r\n");

		sdr("Last-Modified: " + lastModifiedString + "\r\n");

		try {
			sendFile();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}

	private void sendFile() throws Exception {
		byte[] fileInBytes = getFile(fileName);
		int numOfBytes = (int) fileInBytes.length;
		sdr("Content-Length: " + numOfBytes + "\r\n");
		sdr("\r\n");

		socketChannel.write(ByteBuffer.wrap(fileInBytes));
	}

	private void sdr(String response) throws IOException {
		ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
		socketChannel.write(responseBuffer);
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