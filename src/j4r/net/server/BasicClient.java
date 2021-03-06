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

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.InvalidParameterException;

import j4r.net.SocketWrapper;
import j4r.net.TCPSocketWrapper;
import j4r.net.server.AbstractServer.ServerReply;

public class BasicClient implements Closeable {
	
	public static enum ClientRequest {closeConnection}

	public static enum ExceptionType {
		ConnectionFailed("The client failed to connect to the server."),
		ConnectionRejected("The server is busy and cannot process the requests."),
		ConnectionTooLong("The reply from the server has exceeded the allowed time."),
		UnknownErrorWhileConnected("An exception occurred while processing the request.");

		final String text;
		
		ExceptionType(String englishText) {
			this.text = englishText;
		}
		
		@Override
		public String toString() {
			return text;
		}
	}
	
	
	@SuppressWarnings("serial")
	public static class BasicClientException extends Exception {
		private BasicClientException(ExceptionType exceptionType) {
			super(exceptionType.toString());
		}
	}
	
	
	
	private SocketWrapper socketWrapper;
	private boolean open;
	private final int timeout;
	private boolean bypassTimeout;

	
	/**
	 * Constructor.
	 * @param socketAddress the SocketAddress instance that corresponds to the server
	 * @param timeoutSeconds the number of seconds to wait for server reply before throwing a TimeoutException
	 * @throws BasicClientException 
	 */
	protected BasicClient(InetSocketAddress socketAddress, int timeoutSeconds) throws BasicClientException {
		this(socketAddress, timeoutSeconds, true);
	}

	/**
	 * Constructor.
	 * @param socketAddress the SocketAddress instance that corresponds to the server
	 * @param timeoutSeconds the number of seconds to wait for server reply before throwing a TimeoutException
	 * @param isAJavaApplication a boolean that should be set to true by default (false mode is used only for test purpose)
	 * @throws BasicClientException 
	 */
	protected BasicClient(InetSocketAddress socketAddress, int timeoutSeconds, boolean isAJavaApplication) throws BasicClientException {
		if (timeoutSeconds < 0) {
			throw new InvalidParameterException("The timeout delay must be equal to or greater than 0!");
		}
		this.timeout = timeoutSeconds;
		this.bypassTimeout = false;
		try {
			Socket socket = new Socket();
			socket.connect(socketAddress, 5000);
			socketWrapper = new TCPSocketWrapper(socket, isAJavaApplication);
			
		} catch (Exception e) {
			close();
			throw new BasicClientException(ExceptionType.ConnectionFailed);
		} 
		

		if (isAJavaApplication) {
			ServerReply replyFromServer = (ServerReply) readObjectFromServer();
			if (replyFromServer == ServerReply.CallAccepted) {
				open = true;
			} else if (replyFromServer == ServerReply.IAmBusyCallBackLater) {
				open = false;
				close();
				throw new BasicClientException(ExceptionType.ConnectionRejected);
			}
		} else {
			String replyFromServer = (String) readObjectFromServer();
			if (replyFromServer.equals(ServerReply.CallAccepted.name())) {
				open = true;
			} else if (replyFromServer.equals(ServerReply.IAmBusyCallBackLater.name())) {
				open = false;
				close();
				throw new BasicClientException(ExceptionType.ConnectionRejected);
			}
		}
		
	}

	
	protected Object readObjectFromServer() throws BasicClientException {
		try {
			if (bypassTimeout) {
				return socketWrapper.readObject();
			} else {
				return socketWrapper.readObject(timeout);
			}
		} catch (Exception e) {
			close();
			throw handleException(e);
		} 
	}
	
	
	
	private BasicClientException handleException(Exception e) {
		if (e instanceof SocketTimeoutException) {
			return new BasicClientException(ExceptionType.ConnectionTooLong);
		} else {
			return new BasicClientException(ExceptionType.UnknownErrorWhileConnected);
		}
	}


	protected Object processRequest(Object obj) throws BasicClientException {
		if (open) {
			try {
				socketWrapper.writeObject(obj);
			} catch (IOException e) {
				close();
			}
			return readObjectFromServer();
		}
		throw new BasicClientException(ExceptionType.ConnectionFailed);
	}
	
	/**
	 * This method sends a request to the server to close the connection and closes the output and input streams.
	 */
	@Override
	public void close() {
		try {
			if (socketWrapper != null & open) {
				socketWrapper.writeObject(ClientRequest.closeConnection);
			}
		} catch (IOException e) {
		} finally {
			try {
				if (socketWrapper != null) {
					socketWrapper.close();
				}
			} catch (IOException e) {}
		}
	}

	protected void setBypassTimeout(boolean bypass) {
		this.bypassTimeout = bypass;
	}
	
}