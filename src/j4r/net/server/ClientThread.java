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

import java.io.IOException;
import java.net.InetAddress;

import j4r.net.SocketWrapper;
import j4r.net.server.AbstractServer.ServerReply;

public abstract class ClientThread implements Runnable {
	
	protected final AbstractServer.CallReceiverThread receiver;
	protected SocketWrapper socketWrapper;
	
	private final int workerID;
	@SuppressWarnings("unused")
	private InetAddress clientAddress;
	
	private Thread worker;
//	private final Object lock = new Object();
	protected boolean shutdownCall;

	/**
	 * Public constructor.
	 * @param caller a CapsisServer instance
	 * @param workerID an integer that serves to identify this client thread
	 */
	protected ClientThread(AbstractServer.CallReceiverThread receiver, int workerID) {
		super();
		this.receiver = receiver;
		this.workerID = workerID;
	}

	protected void callShutdown() {
		shutdownCall = true;
		interrupt();
	}
	
	@Override
	public void run() {
		while (!shutdownCall) {
			try {
				socketWrapper = receiver.clientQueue.take();
				clientAddress = socketWrapper.getInetAddress();

				processRequest();

				socketWrapper.writeObject(ServerReply.ClosingConnection);
				closeSocket();
			} catch (Exception e) {
				try {
					e.printStackTrace();
					if (!socketWrapper.isClosed()) {
						socketWrapper.writeObject(e);
					}
					closeSocket();
				} catch (IOException e1) {
					socketWrapper = null;
				}
			}
		}
	}

	protected abstract Object processRequest() throws Exception;

	
	protected void start() {
		worker = new Thread(this);
		worker.setName("Client thread no " + workerID);
		worker.start();
	}
	
	protected void interrupt() {
		worker.interrupt();
	}
	
	/**
	 * This method returns the ID of the worker.
	 * @return an Integer
	 */
	protected int getWorkerID() {return workerID;}
	
	protected void closeSocket() throws IOException {
		if (socketWrapper != null && !socketWrapper.isClosed()) {
			socketWrapper.close();
		}
		clientAddress = null;
	}
	
	
	protected SocketWrapper getSocket() {return socketWrapper;}
	
}
