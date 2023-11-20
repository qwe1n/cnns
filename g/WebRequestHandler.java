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

	String WWW_ROOT;
	AsynchronousSocketChannel clientChannel;

	BufferedReader inFromClient;

	private static int cacheSize = 8096;

	private static Map<String, byte[]> cache = new HashMap<>();

	String urlName;
	String userAgent;
	String cookie;
	File fileInfo;
	String fileName;
	String lastModifiedString = null;
	String ifModifiedSinceString = null;

	public WebRequestHandler(AsynchronousSocketChannel clientChannel, String WWW_ROOT)
			throws Exception {

		reqCount++;
		this.WWW_ROOT = WWW_ROOT;

		this.clientChannel = clientChannel;
	}

	void processRequest() throws IOException {

		ByteBuffer buffer = ByteBuffer.allocate(1024);

		clientChannel.read(buffer, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer bytesRead, Void attachment) {
                if (bytesRead > 0) {
                    buffer.flip();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);

                    String request = new String(data, StandardCharsets.UTF_8);
					inFromClient = new BufferedReader(new StringReader(request));

					try {
						boolean b1 = loadRequestHeader();

						if (!b1) {
							outputError(500, "Bad request");
							return;
						}
						int status = mapUrl2File();
						switch (status) {
						case 501: {
							outputError(500, "Bad request");
							break;
						}
						case 404: {
							
							String response =  "HTTP/1.0 404 NOT FOUND\r\n";

							sdr(response);

							byte[] fileInBytes = getFile(fileName);

							int numOfBytes = (int) fileInBytes.length;
							sdr("Content-Length: " + numOfBytes + "\r\n");
							sdr("\r\n");

							clientChannel.write(ByteBuffer.wrap(fileInBytes), null, new CompletionHandler<Integer, Void>() {
								@Override
								public void completed(Integer bytesWritten, Void attachment) {
									try {
										clientChannel.close();
									} catch (IOException e) {
										e.printStackTrace();
									}
								}
								@Override
								public void failed(Throwable exc, Void attachment) {
									System.out.println("Failed to write response: " + exc.getMessage());
								}
							});

							break;
						}
						case 304: {
							try {
								sdr("HTTP/1.0 304" + "Not Modified \r\n");
								String response = "Last-Modified: " + lastModifiedString + "\r\n";
								clientChannel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)), null, new CompletionHandler<Integer, Void>() {
										@Override
										public void completed(Integer bytesWritten, Void attachment) {
											try {
												clientChannel.close();
											} catch (IOException e) {
												e.printStackTrace();
											}
										}
										@Override
										public void failed(Throwable exc, Void attachment) {
											System.out.println("Failed to write response: " + exc.getMessage());
										}
									});
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
					}
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                System.out.println("Failed to read request: " + exc.getMessage());
            }
		});

		

		
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
            clientChannel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)), null, new CompletionHandler<Integer, Void>() {
						@Override
                        public void completed(Integer bytesWritten, Void attachment) {
                            try {
                                clientChannel.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            System.out.println("Failed to write response: " + exc.getMessage());
                        }
                    });
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

			clientChannel.write(ByteBuffer.wrap(output), null, new CompletionHandler<Integer, Void>() {

						@Override
                        public void completed(Integer bytesWritten, Void attachment) {
                            try {
                                clientChannel.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            System.out.println("Failed to write response: " + exc.getMessage());
                        }
                    });

			
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

		byte[] fileInBytes = getFile(fileName);

		int numOfBytes = (int) fileInBytes.length;
		sdr("Content-Length: " + numOfBytes + "\r\n");
		sdr("\r\n");

		clientChannel.write(ByteBuffer.wrap(fileInBytes), null, new CompletionHandler<Integer, Void>() {
						@Override
                        public void completed(Integer bytesWritten, Void attachment) {
                            try {
                                clientChannel.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            System.out.println("Failed to write response: " + exc.getMessage());
                        }
                    });
                
	}

	private void sdr(String response) throws IOException {
		ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
		clientChannel.write(responseBuffer, null, new CompletionHandler<Integer, Void>() {
						@Override
                        public void completed(Integer bytesWritten, Void attachment) {
                            return;
                        }
                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            System.out.println("Failed to write response: " + exc.getMessage());
                        }
                    });
                
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