/*
 * This file is part of the j4r library.
 *
 * Copyright (C) 2020-2021 Her Majesty the Queen in right of Canada
 * Author: Mathieu Fortin, Canadian Wood Fibre Centre, Canadian Forest Service.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed with the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * Please see the license at http://www.gnu.org/copyleft/lesser.html.
 */
package j4r.net.server;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

import j4r.app.AbstractGenericEngine;
import j4r.lang.J4RSystem;
import j4r.net.SocketWrapper;
import j4r.net.TCPSocketWrapper;
import j4r.net.server.ServerTask.ServerTaskID;

public abstract class AbstractServer extends AbstractGenericEngine implements PropertyChangeListener {

	public static enum ServerReply {IAmBusyCallBackLater, 
		CallAccepted, 
		ClosingConnection,
		Done,
		SecurityChecked,
		SecurityFailed,
		EncodingIdentified,
		EncodingUnidentified}

	
	static class J4RSystemExt extends J4RSystem {
		private static List<String> getURLs() throws Exception {
			return J4RSystem.getInternalClassPathURLs();
		}
	}
	
	/**
	 * The BackDoorThread class processes the request one by one and close the socket after
	 * each one of them leaving the ServerSocket free to accept other calls. It is 
	 * a daemon thread because we don't want it to prevent the JVM from exiting.
	 */
	class BackDoorThread extends Thread {
		
		final ServerSocket emergencySocket;
		final int port;
		boolean shutdownCall;
		
		BackDoorThread(int port) throws IOException {
			super("Back door thread");
			setDaemon(true);
			emergencySocket = ServerConfiguration.createServerSocket(port);
			this.port = emergencySocket.getLocalPort();
			start();
		}
		
		@Override
		public void run() {
			while (!shutdownCall) {
				SocketWrapper clientSocket = null;
				try {
					clientSocket = new TCPSocketWrapper(emergencySocket.accept(), false);
					clientSocket.writeObject(ServerReply.CallAccepted);
					if (AbstractServer.this.checkSecurityAndSetEncoding(clientSocket)) {
						Object request = clientSocket.readObject();
						if (request.toString().equals("emergencyShutdown")) {
							if (AbstractServer.this.isPrivate()) {  // emergency shutdown only if the server is private
								System.exit(1);
							}
						} else if (request.toString().equals("softExit")) {
							if (AbstractServer.this.isPrivate()) {  // close socket only if the server is private
								emergencySocket.close();
								break;
							}
						} else if (request.toString().equals("interrupt")) {
							InetAddress clientAddress = clientSocket.getInetAddress();
							for (ClientThread t : AbstractServer.this.whoIsWorkingForWho.keySet()) {
								InetAddress clientOfThisTread = AbstractServer.this.whoIsWorkingForWho.get(t);
								if (clientOfThisTread.equals(clientAddress)) {
									t.interrupt();
								}
							}
						}
					}
				} catch (IOException e1) {
					dealWithException(e1);
				} catch (Exception e2) {
					dealWithException(e2);
				} finally {
					try {
						if (clientSocket != null  && !clientSocket.isClosed()) {
							clientSocket.close();
						}
					} catch (IOException e) {
						dealWithException(e);
					}
				}
			}
		}

		void callShutdown() {
			shutdownCall = true;
			try {
				emergencySocket.close();
			} catch (IOException e) {}
		}
		
		void dealWithException(Exception e) {
			if (!shutdownCall) {
				e.printStackTrace();
			}
		}
		
		protected void softExit() {
			try {
				Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
				SocketWrapper socketWrapper = new TCPSocketWrapper(socket, false);
				socketWrapper.readObject();
				socketWrapper.writeObject("softExit");
				socketWrapper.close();
			} catch (Exception e) {}
		}
	}

	/**
	 * This internal class handles the calls and stores these in the queue.
	 * @author Mathieu Fortin
	 */
	class CallReceiverThread extends Thread {

		private boolean shutdownCall;
		final ServerSocket serverSocket;
		final LinkedBlockingQueue<SocketWrapper> clientQueue;
		final int id;
		
		/**
		 * General constructor. 
		 * @param serverSocket a ServerSocket instance if null then the protocol is assumed to be UDP with a single client
		 * @param clientQueue
		 * @param maxNumberOfWaitingClients
		 */
		private CallReceiverThread(ServerSocket serverSocket, int id) {
			this.serverSocket = serverSocket;
			this.id = id;
			clientQueue = new LinkedBlockingQueue<SocketWrapper>();
			shutdownCall = false;
			setName("Answering call thread " + this.id);
		}
		
		/*
		 * The swingworker
		 */
		@Override
		public void run() {
			try {
				while (!shutdownCall) {
					SocketWrapper clientSocket = new TCPSocketWrapper(serverSocket.accept(), AbstractServer.this.isCallerAJavaApplication);
					clientSocket.writeObject(ServerReply.CallAccepted);
					if (AbstractServer.this.checkSecurityAndSetEncoding(clientSocket)) {
						clientQueue.add(clientSocket);
					}
				}
				AbstractGenericEngine.J4RLogger.log(Level.INFO, "Call receiver thread shut down");
			} catch (Exception e) {
				if (!shutdownCall) {
					e.printStackTrace();
				}
			} finally {
				try {
					if (serverSocket != null) {
						serverSocket.close();
					}	
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		
		void callShutdown() {
			shutdownCall = true;
			clientQueue.clear();
			try {
				if (serverSocket != null) {
					serverSocket.close();
				}
			} catch (IOException e) {
				interrupt();
			} finally {
				try {
					join(5000);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

	}


	private final ArrayList<ClientThread> clientThreads;
	private final ArrayList<ClientThread> gcThreads;
	
	protected final List<CallReceiverThread> callReceiverThreads;
	protected final CallReceiverThread gcReceiverThread;
	protected final ConcurrentHashMap<ClientThread, InetAddress> whoIsWorkingForWho;
	protected final BackDoorThread backdoorThread;

	protected final boolean isCallerAJavaApplication;
	
	private final ServerConfiguration configuration;

	private List<PropertyChangeListener> listeners;

	/**
	 * Constructor.
	 * @param configuration a ServerConfiguration instance that defines the number of threads, the reference path and the filename of the exception rules
	 * @param isCallerAJavaApplication true if the client is a Java app 
	 * @throws Exception
	 */
	protected AbstractServer(ServerConfiguration configuration, boolean isCallerAJavaApplication) throws Exception {
		this.configuration = configuration;
		this.isCallerAJavaApplication = isCallerAJavaApplication;
		clientThreads = new ArrayList<ClientThread>();
		gcThreads = new ArrayList<ClientThread>();
		this.whoIsWorkingForWho = new ConcurrentHashMap<ClientThread, InetAddress>();
		callReceiverThreads = new ArrayList<CallReceiverThread>();
		try {
			List<ServerSocket> serverSockets = configuration.createServerSockets();
			int i = 1;
			for (ServerSocket ss : serverSockets) {
				CallReceiverThread crt = new CallReceiverThread(ss, i);
				callReceiverThreads.add(crt);
				for (int j = 1; j <= configuration.numberOfClientThreadsPerReceiver; j++) {
					clientThreads.add(createClientThread(crt, i * 1000 + j));		// i + 1 serves as id
				}
				i++;
			}
	
			backdoorThread = new BackDoorThread(configuration.internalPorts[0]);
			ServerSocket gcServerSocket = ServerConfiguration.createServerSocket(configuration.internalPorts[1]);
			if (!this.isPrivate()) {
				System.out.println("Emergency port is " + backdoorThread.port);
				System.out.println("Garbage collection port is " + gcServerSocket.getLocalPort());
			}
			
			gcReceiverThread = new CallReceiverThread(gcServerSocket, 99);
			for (int j = 1; j <= configuration.numberOfClientThreadsPerReceiver; j++) {
				gcThreads.add(createClientThread(gcReceiverThread, 99 * 1000 + j));		// i + 1 serves as id
			}
			
			if (!this.isPrivate()) {
				System.out.println("Current class path:");
				List<String> urls = J4RSystemExt.getURLs();
				for (String url : urls) {
					System.out.println(url);
				}
			}
			
		} catch (BindException e1) {
			throw e1;
		} catch (IOException e2) {
			throw new Exception("Unable to initialize the server: " + e2.getMessage());
		}
		listeners = new CopyOnWriteArrayList<PropertyChangeListener>();
	}

	
	protected boolean checkSecurityAndSetEncoding(SocketWrapper clientSocket) {
		try {
			Object obj = clientSocket.readObject();
			int key = Integer.parseInt(obj.toString());
			if (configuration.key == key) {
				clientSocket.writeObject(ServerReply.SecurityChecked);
				if (clientSocket.checkEncoding(getPotentialCharsets())) {
					clientSocket.writeObject(ServerReply.EncodingIdentified);
				} else {
					clientSocket.writeObject(ServerReply.EncodingUnidentified);
				};
				return true;
			} else {
				clientSocket.writeObject(ServerReply.SecurityFailed);
				return false;
			}
		} catch (Exception e) {
			try {
				clientSocket.writeObject(e);
			} catch (IOException e1) {
				e.printStackTrace();
			}
			return false;
		}
	}
	
	protected abstract List<Charset> getPotentialCharsets();

	protected abstract ClientThread createClientThread(CallReceiverThread receiverThread, int id);
		

//	/**
//	 * This method waits until the head of the queue is non null and returns the socket.
//	 * @return a Socket instance
//	 * @throws InterruptedException 
//	 */
//	protected synchronized SocketWrapper getWaitingClients() throws InterruptedException {
//		SocketWrapper socket = clientQueue.take();
//		return socket;
//	}

	/**
	 * This method starts the worker thread, which listens to the clients in the queue.
	 */
	protected void listenToClients() {	
		for (ClientThread t : clientThreads) {
			t.start();
		}
		for (ClientThread t : gcThreads) {
			t.start();
		}
	}

	/**
	 * This method starts the callReceiver thread that handles the call from the clients.
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	protected void startReceiverThread() throws ExecutionException, InterruptedException {
		listenToClients();
		for (CallReceiverThread t : callReceiverThreads) {
			t.start();
		}
		gcReceiverThread.start();
		AbstractGenericEngine.J4RLogger.log(Level.INFO, "Server started");
	}

	/**
	 * Return the server configuration.
	 * @return a ServerConfiguration instance
	 */
	protected ServerConfiguration getConfiguration() {
		return configuration;
	}

	/**
	 * Return true if the server is a private on.
	 * @return a boolean
	 */
	public boolean isPrivate() {
		return getConfiguration().isPrivateServer();
	}

	/**
	 * This method returns the vector of client threads.
	 * @return a Vector of ClientThread instances
	 */
	protected List<ClientThread> getClientThreads() {return clientThreads;}


//	protected void closeAndRestartTheseThreads(Collection<ClientThread> connectionsToBeClosed) {
//		for (ClientThread thread : connectionsToBeClosed) {
//			try {
//				thread.getSocket().close();
//				thread.restartAction();
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
//		}
//	}


	@Override
	protected void firstTasksToDo() {
		addTask(new ServerTask(ServerTaskID.StartReceiverThread, this));
		if (isPrivate()) {
			addTask(new ServerTask(ServerTaskID.CreateFileInfo, this));
		}
	}

	public void addPropertyChangeListener(PropertyChangeListener listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener);
		}
	}
	
	public void removePropertyChangeListener(PropertyChangeListener listener) {
		listeners.remove(listener);
	}
	
	/*
	 * Just to extend the visibility
	 */
	protected void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
		PropertyChangeEvent evt = new PropertyChangeEvent("Server event", propertyName, oldValue, newValue);
		for (PropertyChangeListener listener : listeners) {
			listener.propertyChange(evt);
		}
	}


	@Override
	public void propertyChange(PropertyChangeEvent evt) {
		String propertyName = evt.getPropertyName();
		if (propertyName.equals("shutdownServer")) {
			requestShutdown();
		} 
	}

	@Override
	public void requestShutdown() {
		List<CallReceiverThread> crt = new ArrayList<CallReceiverThread>();
		crt.addAll(callReceiverThreads);
		if (gcReceiverThread != null) {
			crt.add(gcReceiverThread);
		}
		for (CallReceiverThread t : crt) {
			t.callShutdown();
		}
		List<ClientThread> ct = new ArrayList<ClientThread>();
		ct.addAll(clientThreads);
		ct.addAll(gcThreads);
		for (ClientThread t : ct) {
			t.callShutdown();
		}
		if (backdoorThread != null) {
			backdoorThread.callShutdown();
		}
 		super.requestShutdown();
	}

	@Override
	protected void shutdown(int shutdownCode) {
		if (backdoorThread != null && backdoorThread.isAlive()) {
			backdoorThread.softExit();
		}
		if (isPrivate()) {		// private server also shuts down the JVM
			super.shutdown(shutdownCode);
		}
	}

	/*
	 * This method can be overriden and left empty for webserver.
	 * @throws IOException
	 */
	protected abstract void createFileInfoForLocalServer() throws IOException;

}
