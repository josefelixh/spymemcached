// Copyright (c) 2006  Dustin Sallings <dustin@spy.net>

package net.spy.memcached;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.spy.SpyObject;
import net.spy.memcached.ops.Operation;
import net.spy.memcached.ops.OperationException;

/**
 * Connection to a cluster of memcached servers.
 */
public class MemcachedConnection extends SpyObject {
	// The number of empty selects we'll allow before taking action.  It's too
	// easy to write a bug that causes it to loop uncontrollably.  This helps
	// find those bugs and often works around them.
	private static final int EXCESSIVE_EMPTY = 100;
	// maximum amount of time to wait between reconnect attempts
	private static final long MAX_DELAY = 30000;
	// Maximum number of sequential errors before reconnecting.
	private static final int EXCESSIVE_ERRORS = 1;

	private volatile boolean shutDown=false;
	// If true, get optimization will collapse multiple sequential get ops
	private boolean optimizeGets=true;
	private Selector selector=null;
	private MemcachedNode[] connections=null;
	private int emptySelects=0;
	// AddedQueue is used to track the QueueAttachments for which operations
	// have recently been queued.
	private final ConcurrentLinkedQueue<MemcachedNode> addedQueue;
	// reconnectQueue contains the attachments that need to be reconnected
	// The key is the time at which they are eligible for reconnect
	private final SortedMap<Long, MemcachedNode> reconnectQueue;

	/**
	 * Construct a memcached connection.
	 *
	 * @param bufSize the size of the buffer used for reading from the server
	 * @param f the factory that will provide an operation queue
	 * @param a the addresses of the servers to connect to
	 *
	 * @throws IOException if a connection attempt fails early
	 */
	public MemcachedConnection(int bufSize, ConnectionFactory f,
			List<InetSocketAddress> a)
		throws IOException {
		reconnectQueue=new TreeMap<Long, MemcachedNode>();
		addedQueue=new ConcurrentLinkedQueue<MemcachedNode>();
		selector=Selector.open();
		connections=new MemcachedNode[a.size()];
		int cons=0;
		for(SocketAddress sa : a) {
			SocketChannel ch=SocketChannel.open();
			ch.configureBlocking(false);
			MemcachedNode qa=new MemcachedNode(cons, sa, ch, bufSize,
				f.createOperationQueue(), f.createOperationQueue(),
				f.createOperationQueue());
			int ops=0;
			if(ch.connect(sa)) {
				getLogger().info("Connected to %s immediately", qa);
				qa.reconnectAttempt=0;
				assert ch.isConnected();
			} else {
				getLogger().info("Added %s to connect queue", qa);
				ops=SelectionKey.OP_CONNECT;
			}
			qa.sk=ch.register(selector, ops, qa);
			assert ch.isConnected()
				|| qa.sk.interestOps() == SelectionKey.OP_CONNECT
				: "Not connected, and not wanting to connect";
			connections[cons++]=qa;
		}
	}

	/**
	 * Enable or disable get optimization.
	 *
	 * When enabled (default), multiple sequential gets are collapsed into one.
	 */
	public void setGetOptimization(boolean to) {
		optimizeGets=to;
	}

	private boolean selectorsMakeSense() {
		for(MemcachedNode qa : connections) {
			if(qa.sk.isValid()) {
				if(qa.channel.isConnected()) {
					int sops=qa.sk.interestOps();
					int expected=0;
					if(qa.hasReadOp()) {
						expected |= SelectionKey.OP_READ;
					}
					if(qa.hasWriteOp()) {
						expected |= SelectionKey.OP_WRITE;
					}
					if(qa.toWrite > 0) {
						expected |= SelectionKey.OP_WRITE;
					}
					assert sops == expected : "Invalid ops:  "
						+ qa + ", expected " + expected + ", got " + sops;
				} else {
					int sops=qa.sk.interestOps();
					assert sops == SelectionKey.OP_CONNECT
					: "Not connected, and not watching for connect: "
						+ sops;
				}
			}
		}
		getLogger().debug("Checked the selectors.");
		return true;
	}

	/**
	 * MemcachedClient calls this method to handle IO over the connections.
	 */
	@SuppressWarnings("unchecked")
	public void handleIO() throws IOException {
		if(shutDown) {
			throw new IOException("No IO while shut down");
		}

		// Deal with all of the stuff that's been added, but may not be marked
		// writable.
		handleInputQueue();
		getLogger().debug("Done dealing with queue.");

		long delay=0;
		if(!reconnectQueue.isEmpty()) {
			long now=System.currentTimeMillis();
			long then=reconnectQueue.firstKey();
			delay=Math.max(then-now, 1);
		}
		getLogger().debug("Selecting with delay of %sms", delay);
		assert selectorsMakeSense() : "Selectors don't make sense.";
		int selected=selector.select(delay);
		Set<SelectionKey> selectedKeys=selector.selectedKeys();

		if(selectedKeys.isEmpty()) {
			getLogger().debug("No selectors ready, interrupted: "
					+ Thread.interrupted());
			if(++emptySelects > EXCESSIVE_EMPTY) {
				for(SelectionKey sk : selector.keys()) {
					getLogger().info("%s has %s, interested in %s",
							sk, sk.readyOps(), sk.interestOps());
					if(sk.readyOps() != 0) {
						getLogger().info("%s has a ready op, handling IO", sk);
						handleIO(sk);
					} else {
						queueReconnect((MemcachedNode)sk.attachment());
					}
				}
				assert emptySelects < EXCESSIVE_EMPTY + 10
					: "Too many empty selects";
			}
		} else {
			getLogger().debug("Selected %d, selected %d keys",
					selected, selectedKeys.size());
			emptySelects=0;
			for(SelectionKey sk : selectedKeys) {
				getLogger().debug(
						"Got selection key:  %s (r=%s, w=%s, c=%s, op=%s)",
						sk, sk.isReadable(), sk.isWritable(),
						sk.isConnectable(), sk.attachment());
				handleIO(sk);
			} // for each selector
			selectedKeys.clear();
		}

		if(!reconnectQueue.isEmpty()) {
			attemptReconnects();
		}
	}

	// Handle any requests that have been made against the client.
	private void handleInputQueue() {
		if(!addedQueue.isEmpty()) {
			getLogger().debug("Handling queue");
			// If there's stuff in the added queue.  Try to process it.
			Collection<MemcachedNode> toAdd=new HashSet<MemcachedNode>();
			try {
				MemcachedNode qa=null;
				while((qa=addedQueue.remove()) != null) {
					boolean readyForIO=false;
					if(qa.channel != null && qa.channel.isConnected()) {
						Operation op=qa.getCurrentWriteOp();
						if(op != null) {
							readyForIO=true;
							getLogger().debug("Handling queued write %s", qa);
						}
					} else {
						toAdd.add(qa);
					}
					qa.copyInputQueue();
					if(readyForIO) {
						try {
							if(qa.wbuf.hasRemaining()) {
								handleWrites(qa.sk, qa);
							}
						} catch(IOException e) {
							getLogger().warn("Exception handling write", e);
							queueReconnect(qa);
						}
					}
					fixupOps(qa);
				}
			} catch(NoSuchElementException e) {
				// out of stuff.
			}
			addedQueue.addAll(toAdd);
		}
	}

	// Handle IO for a specific selector.  Any IOException will cause a
	// reconnect
	private void handleIO(SelectionKey sk) {
		assert !sk.isAcceptable() : "We don't do accepting here.";
		MemcachedNode qa=(MemcachedNode)sk.attachment();
		if(sk.isConnectable()) {
			getLogger().info("Connection state changed for %s", sk);
			try {
				if(qa.channel.finishConnect()) {
					assert qa.channel.isConnected() : "Not connected.";
					qa.reconnectAttempt=0;
					addedQueue.offer(qa);
					if(qa.wbuf.hasRemaining()) {
						handleWrites(sk, qa);
					}
				} else {
					assert !qa.channel.isConnected() : "connected";
				}
			} catch(IOException e) {
				getLogger().warn("Problem handling connect", e);
				queueReconnect(qa);
			}
		} else {
			if(sk.isWritable()) {
				try {
					handleWrites(sk, qa);
				} catch (IOException e) {
					getLogger().info("IOException handling %s, reconnecting",
							qa.getCurrentWriteOp(), e);
					queueReconnect(qa);
				}
			}
			if(sk.isReadable()) {
				try {
					handleReads(sk, qa);
					qa.protocolErrors=0;
				} catch (OperationException e) {
					if(++qa.protocolErrors >= EXCESSIVE_ERRORS) {
						queueReconnect(qa);
					}
				} catch (IOException e) {
					getLogger().info("IOException handling %s, reconnecting",
							qa.getCurrentReadOp(), e);
					queueReconnect(qa);
				}
			}
		}
		fixupOps(qa);
	}

	private void handleWrites(SelectionKey sk, MemcachedNode qa)
		throws IOException {
		qa.fillWriteBuffer(optimizeGets);
		boolean canWriteMore=qa.toWrite > 0;
		while(canWriteMore) {
			int wrote=qa.channel.write(qa.wbuf);
			assert wrote >= 0 : "Wrote negative bytes?";
			qa.toWrite -= wrote;
			assert qa.toWrite >= 0
				: "toWrite went negative after writing " + wrote
					+ " bytes for " + qa;
			getLogger().debug("Wrote %d bytes", wrote);
			qa.fillWriteBuffer(optimizeGets);
			canWriteMore = wrote > 0 && qa.toWrite > 0;
		}
	}

	private void handleReads(SelectionKey sk, MemcachedNode qa)
		throws IOException {
		Operation currentOp = qa.getCurrentReadOp();
		int read=qa.channel.read(qa.rbuf);
		while(read > 0) {
			getLogger().debug("Read %d bytes", read);
			qa.rbuf.flip();
			while(qa.rbuf.remaining() > 0) {
				assert currentOp != null : "No read operation";
				currentOp.readFromBuffer(qa.rbuf);
				if(currentOp.getState() == Operation.State.COMPLETE) {
					getLogger().debug(
							"Completed read op: %s and giving the next %d bytes",
							currentOp, qa.rbuf.remaining());
					Operation op=qa.removeCurrentReadOp();
					assert op == currentOp
					: "Expected to pop " + currentOp + " got " + op;
					currentOp=qa.getCurrentReadOp();
				}
			}
			qa.rbuf.clear();
			read=qa.channel.read(qa.rbuf);
		}
	}

	private void fixupOps(MemcachedNode qa) {
		if(qa.sk.isValid()) {
			int iops=qa.getSelectionOps();
			getLogger().debug("Setting interested opts to %d", iops);
			qa.sk.interestOps(iops);
		} else {
			getLogger().debug("Selection key is not valid.");
		}
	}

	// Make a debug string out of the given buffer's values
	static String dbgBuffer(ByteBuffer b, int size) {
		StringBuilder sb=new StringBuilder();
		byte[] bytes=b.array();
		for(int i=0; i<size; i++) {
			char ch=(char)bytes[i];
			if(Character.isWhitespace(ch) || Character.isLetterOrDigit(ch)) {
				sb.append(ch);
			} else {
				sb.append("\\x");
				sb.append(Integer.toHexString(bytes[i] & 0xff));
			}
		}
		return sb.toString();
	}

	private void queueReconnect(MemcachedNode qa) {
		if(!shutDown) {
			getLogger().warn("Closing, and reopening %s, attempt %d.",
					qa, qa.reconnectAttempt);
			if(qa.sk != null) {
				qa.sk.cancel();
				assert !qa.sk.isValid() : "Cancelled selection key is valid";
			}
			qa.reconnectAttempt++;
			try {
				qa.channel.socket().close();
			} catch(IOException e) {
				getLogger().warn("IOException trying to close a socket", e);
			}
			qa.channel=null;

			long delay=Math.min((100*qa.reconnectAttempt) ^ 2, MAX_DELAY);

			reconnectQueue.put(System.currentTimeMillis() + delay, qa);

			// Need to do a little queue management.
			qa.setupResend();
		}
	}

	private void attemptReconnects() throws IOException {
		long now=System.currentTimeMillis();
		for(Iterator<MemcachedNode> i=
				reconnectQueue.headMap(now).values().iterator(); i.hasNext();) {
			MemcachedNode qa=i.next();
			i.remove();
			getLogger().info("Reconnecting %s", qa);
			SocketChannel ch=SocketChannel.open();
			ch.configureBlocking(false);
			int ops=0;
			if(ch.connect(qa.socketAddress)) {
				getLogger().info("Immediately reconnected to %s", qa);
				assert ch.isConnected();
			} else {
				ops=SelectionKey.OP_CONNECT;
			}
			qa.channel=ch;
			qa.sk=ch.register(selector, ops, qa);
		}
	}

	/**
	 * Get the number of connections currently handled.
	 */
	public int getNumConnections() {
		return connections.length;
	}

	/**
	 * Get the remote address of the socket with the given ID.
	 * 
	 * @param which which id
	 * @return the rmeote address
	 */
	public SocketAddress getAddressOf(int which) {
		return connections[which].socketAddress;
	}

	/**
	 * Add an operation to the given connection.
	 * 
	 * @param which the connection offset
	 * @param o the operation
	 */
	public void addOperation(int which, Operation o) {
		boolean placed=false;
		int pos=which;
		int loops=0;
		// Loop until we find an appropriate server to process this operation
		// beginning with the requested position.
		while(!placed) {
			assert loops < 3 : "Too many loops!";
			MemcachedNode qa=connections[pos];
			if(which == pos) {
				loops++;
			}
			// If reconnectAttempt == 0, this client is not in the process of
			// reconnecting.  If loops > 0, we've already been through all the
			// clients and just want to queue the new item where it was
			// originally requested (where it'll wait for the node to return)
			if(qa.reconnectAttempt == 0 || loops > 1) {
				o.initialize();
				qa.addOp(o);
				addedQueue.offer(qa);
				Selector s=selector.wakeup();
				assert s == selector : "Wakeup returned the wrong selector.";
				getLogger().debug("Added %s to %d", o, which);
				placed=true;
			} else {
				if(++pos >= connections.length) {
					pos=0;
				}
			}
		}
	}

	/**
	 * Shut down all of the connections.
	 */
	public void shutdown() throws IOException {
		for(MemcachedNode qa : connections) {
			if(qa.channel != null) {
				qa.channel.close();
				qa.sk=null;
				if(qa.toWrite > 0) {
					getLogger().warn(
						"Shut down with %d bytes remaining to write",
							qa.toWrite);
				}
				getLogger().debug("Shut down channel %s", qa.channel);
			}
		}
		selector.close();
		getLogger().debug("Shut down selector %s", selector);
	}

	@Override
	public String toString() {
		StringBuilder sb=new StringBuilder();
		sb.append("{MemcachedConnection to");
		for(MemcachedNode qa : connections) {
			sb.append(" ");
			sb.append(qa.socketAddress);
		}
		sb.append("}");
		return sb.toString();
	}

}
