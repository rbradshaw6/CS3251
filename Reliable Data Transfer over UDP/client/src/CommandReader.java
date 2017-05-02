import java.util.Scanner;

/*
 * This class implements a non-blocking command reader
 * that reads input from stdin. An instance of this
 * class should be run in its own thread.
 */
public class CommandReader implements Runnable {
	private Scanner scanner;
	private String cmd;
	private boolean closed;
	
	public CommandReader() {
		this.scanner = new Scanner( System.in );
		this.scanner.useDelimiter( "\n" );

		this.cmd = "";
		this.closed = false;
	}

	@Override
	public void run() {
		// As long as we haven't been told to close, read input
		while (!this.closed) {
			if (this.scanner.hasNext())
				this.cmd = scanner.next();
		}
	}
	
	/*
	 * Return the latest command we received and
	 * clear our input buffer for the next command.
	 */
	public String getCommand()
	{
		String retval = this.cmd;
		this.cmd = "";
		return retval;
	}
	
	public void closeScanner()
	{
		closed = true;
	}
}