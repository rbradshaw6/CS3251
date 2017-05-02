import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import reldat.ReldatConnection;

public class ReldatClient {
	public static void main(String[] args) {
		if(args.length != 2)
			usage();

		// Match the first arg against the format: <IPv4 address>:<port>
		Pattern hostRegex = Pattern.compile("^(\\d{1,3}[.]\\d{1,3}[.]\\d{1,3}[.]\\d{1,3}):(\\d{1,5})$");
		Matcher hostMatch = hostRegex.matcher(args[0]);

		// If no match, a valid host was not passed in - print usage and exit
		if(!hostMatch.matches())
			usage();

		String ipAddress = hostMatch.group(1);
		int port = Integer.parseInt(hostMatch.group(2));

		// If the port is too big to be valid, print usage and exit
		if(port > 65535)
			usage();

		// Arg 2 is the max window size
		int maxReceiveWindowSize = Integer.parseInt(args[1]);

		// Create a new RELDAT connection using the specified max window size
		ReldatConnection reldatConn = new ReldatConnection(maxReceiveWindowSize);

		try {
			// If we successfully connect to a host, enter the command loop
			if (reldatConn.connect(ipAddress, port))
				commandLoop(reldatConn);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		// Explicitly exit to terminate the command thread if it doesn't successfully join()
		System.exit(0);
	}

	private static void usage() {
		System.out.println( "Usage: java ReldatClient <host IP address>:<host port> <max receive window size in packets>" );
		System.exit( 0 );
	}

	private static void commandLoop(ReldatConnection reldatConn) {
		System.out.print( "> " );

		// Create a new thread so we can read in commands without blocking
		CommandReader cr = new CommandReader();
		Thread cmdInput  = new Thread(cr);
		cmdInput.start();
		
		String transformedData = "";
		
		connectionLoop: {
			while (true) {
				String clientInput = cr.getCommand();

				Pattern commandRegex = Pattern.compile("(\\w+)\\s*(.+)?");
				Matcher commandMatch = commandRegex.matcher(clientInput);

				if (commandMatch.matches()) {
					String command = commandMatch.group(1);

					switch (command) {
						case "disconnect":
							// Just break out of this loop to enter the disconnect phase
							break connectionLoop;
						case "transform":
							// Get the filename
							String fileName = commandMatch.group(2);
							
							// Ensure the filename is valid
							if (fileName == null || fileName.isEmpty())
								System.out.println("  Usage: transform <file>");
							else {
								String messageToSend = null;

								try {
									messageToSend = readFileToString(fileName);
									transformedData = reldatConn.conversation(messageToSend);

									// If null was returned, then we lost connection to the server, so quit
									if (transformedData == null)
										break connectionLoop;
									
									outputTransformed(fileName, transformedData);
								} catch (FileNotFoundException e) {
									System.out.println("  File not found: " + fileName);
								}
							}

							break;
						default:
							// All other commands are unrecognized
							System.out.println(
								"Unrecognized command " +
								command +
								". Valid commands are:\n" +
								"    disconnect\n" +
								"    transform"
							);

							break;
					}

					System.out.print("> ");
				}
				
				reldatConn.listen();
			}
		}
		
		cr.closeScanner();

		try {
			cmdInput.join(1);
		} catch (InterruptedException e) {
			// Do nothing, because we're going to exit anyway
		}
		
		// The return value of the method that gives us transformed data
		// can return null if the server crashed. If that happens, we know
		// not to try to disconnect from the (unreachable) server.
		if (transformedData != null)
			reldatConn.disconnect();
	}
	
	/*
	 * Read the contents of a file into RAM.
	 */
	private static String readFileToString(String filename) throws FileNotFoundException {
		String newStr = "";

		BufferedReader br = new BufferedReader(new FileReader(filename));
		String sCurrentLine = null;
		
		try {
			while ((sCurrentLine = br.readLine()) != null) {
				// readLine() trims newlines, so re-append them
				newStr += sCurrentLine + "\n";
			}
			
			br.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

        return newStr;
	}
	
	/*
	 * Given a filename in the form of <filename>.<extension> and a string,
	 * write the string to the file <filename>-received.<extension>.
	 */
	private static void outputTransformed(String originalFilename, String transformedData) {
		// Get the filename and extension from the original filename
		String[] tokens = originalFilename.split("\\.(?=[^\\.]+$)");
		
		// Append "-received" to the filename
		String newFilename = tokens[0] + "-received";
		
		// Append the extension if there is one
		if(tokens.length == 2)
			newFilename += "." + tokens[1];

		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(newFilename));
			bw.write(transformedData);
			bw.close();
			System.out.println("Created filed with transformed data: " + newFilename);
		} catch (IOException e) {
			System.err.println("Could not write file: " + newFilename);
		}
	}
}
