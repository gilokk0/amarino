/*
  Amarino - A prototyping software toolkit for Android and Arduino
  Copyright (c) 2010 Bonifaz Kaufmann.  All right reserved.
  
  This application and its library is free software; you can redistribute
  it and/or modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation; either
  version 3 of the License, or (at your option) any later version.

  This library is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public
  License along with this library; if not, write to the Free Software
  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
*/
package at.abraxas.amarino;

import it.gerdavax.easybluetooth.BtSocket;
import it.gerdavax.easybluetooth.LocalDevice;
import it.gerdavax.easybluetooth.ReadyListener;
import it.gerdavax.easybluetooth.RemoteDevice;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import at.abraxas.amarino.log.Logger;

/**
 * $Id: AmarinoService.java 444 2010-06-10 13:11:59Z abraxas $
 */
public class AmarinoService extends Service {
	
	private static final int NOTIFY_ID = 119561;
	private static final String TAG = "AmarinoService";
	
	private static final int BUSY = 1;
	private static final int ACTIVE_CONNECTIONS = 2;
	private static final int NO_CONNECTIONS = 3;
	
	/**
	 * Defines how many simultaneous Connections can be handled
	 */
	private static final int MAX_CONNECTIONS = 2;
	
	private final IBinder binder = new AmarinoServiceBinder();
	
	private LocalDevice localDevice;
	private PendingIntent launchIntent;
	private Notification notification;
	private NotificationManager notifyManager;
	private AmarinoDbAdapter db;
	
	/* most ppl will only use one Bluetooth device, thus lets start with capacity 1, <address, running thread> */
	//TODO: changes capacity for more simultaneous connections
	private HashMap<String, ConnectedThread> connections = new HashMap<String, ConnectedThread>(MAX_CONNECTIONS);
	
	/* need to know which plugin has been activated for which device, <pluginId, list of devices> */
	private HashMap<Integer, List<Device>> enabledEvents = new HashMap<Integer, List<Device>>();
	
	private int serviceState = NO_CONNECTIONS;
	
	@Override
	public void onCreate() {
		Logger.d(TAG, "Background service created");
		super.onCreate();
		
		db = new AmarinoDbAdapter(this);
		
		initNotificationManager();
		
		// initialize reflection methods for backward compatibility of start and stopForeground
		try {
            mStartForeground = getClass().getMethod("startForeground",
                    mStartForegroundSignature);
            mStopForeground = getClass().getMethod("stopForeground",
                    mStopForegroundSignature);
        } catch (NoSuchMethodException e) {
            // Running on an older platform.
            mStartForeground = mStopForeground = null;
        }
		
		IntentFilter filter = new IntentFilter(AmarinoIntent.ACTION_SEND);
		registerReceiver(receiver, filter);
	}


	
	@Override
	public void onStart(Intent intent, int startId) {
		handleStart(intent, startId);
	}
	
	@Override
    public int onStartCommand(Intent intent, int flags, int startId) {
		return handleStart(intent, startId);
    }
	
	private int handleStart(Intent intent, int startId){
		//Logger.d(TAG, "onStart");
		super.onStart(intent, startId);
		
		if (intent == null) {
			// here we might restore our state if we got killed by the system
			// TODO
			return START_STICKY;
		}

		String action = intent.getAction();
		if (action == null) return START_STICKY;
		
		// someone wants to send data to arduino
		if (action.equals(AmarinoIntent.ACTION_SEND)){
			forwardDataToArduino(intent);
			return START_NOT_STICKY;
		}
		
		// publish the state of devices
		if (action.equals(AmarinoIntent.ACTION_GET_CONNECTED_DEVICES)){
			broadcastConnectedDevicesList();
			return START_NOT_STICKY;
		}
		
		// this intent is used to surely disable all plug-ins
		// if a user forgot to call force disable after force enable was called
		if (action.equals(AmarinoIntent.ACTION_DISABLE_ALL)){
			if (serviceState == NO_CONNECTIONS) {
				disableAllPlugins();
				stopSelf();
			}
			return START_NOT_STICKY;
		}
		
		/* --- CONNECT and DISCONNECT part --- */
		String address = intent.getStringExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS);
		int type = intent.getIntExtra(AmarinoIntent.EXTRA_DEVICE_TYPE, -1);
				//StringExtra(AmarinoIntent.EXTRA_DEVICE_TYPE);
		if (address == null) {
			Logger.d(TAG, "EXTRA_DEVICE_ADDRESS not found!");
			return START_NOT_STICKY;
		}

		if (type == -1) {
			Logger.d(TAG, "EXTRA_DEVICE_TYPE not found!");
			return START_NOT_STICKY;
		}
		
		// connect and disconnect operations may take some time
		// we don't want to shutdown our service while it does some work
		serviceState = BUSY;
		
		if (!Amarino.isCorrectAddressFormat(address)) {
			Logger.d(TAG, getString(R.string.service_address_invalid, address));
			sendConnectionFailed(address);
			shutdownServiceIfNecessary();
		}
		else {
			if (AmarinoIntent.ACTION_CONNECT.equals(action)){
				Logger.d(TAG, "ACTION_CONNECT request received");
				connect(address, type);
			}
			else if (AmarinoIntent.ACTION_DISCONNECT.equals(action)){
				Logger.d(TAG, "ACTION_DISCONNECT request received");
				disconnect(address, type);
			}
		}

		return START_STICKY;
	}


	private void forwardDataToArduino(Intent intent){
		
		final int pluginId = intent.getIntExtra(AmarinoIntent.EXTRA_PLUGIN_ID, -1);
		// Log.d(TAG, "send from pluginID: " + pluginId);
		if (pluginId == -1) {
			// intent sent from another app which is not a plugin
			final String address = intent.getStringExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS);
			final int type = intent.getIntExtra(AmarinoIntent.EXTRA_DATA_TYPE, -1);
			if (address == null) {
				Logger.d(TAG, "Data not sent! EXTRA_DEVICE_ADDRESS not set.");
				return;
			}

			byte[] message;
			try {
				message = MessageBuilder.getMessage(intent);
				if (message == null) return; 
				
				// cutoff leading flag and ACK_FLAG for logger
				Logger.d(TAG, getString(R.string.service_message_to_send, message));
				
				sendData(address, message, type);
			} catch (IOException e) {
				e.printStackTrace();
			}

		}
		else {
			List<Device> devices = enabledEvents.get(pluginId);
			
			if (devices != null && devices.size() != 0){
				for (Device device : devices){
					// we have to put the flag into the intent in order to fulfill the message builder requirements
					intent.putExtra(AmarinoIntent.EXTRA_FLAG, device.events.get(pluginId).flag);
					//Log.d(TAG, "flag" + device.events.get(pluginId).flag);
					
					byte[] message;
					try {
						message = MessageBuilder.getMessage(intent);
						if (message == null) return;
						
						Logger.d(TAG, getString(R.string.service_message_to_send, message));

						sendData(device.getAddress(), message, device.getType());
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			else {
				Logger.d(TAG, "No device associated with plugin: " + pluginId);
			}
		}
	}



	@Override
	public void onDestroy() {
		super.onDestroy();
		Logger.d(TAG, "Background service stopped");
		
		// we do only stop our service if no connections are active, however Android may kill our service without warning
		// clean up in case service gets killed from the system due to low memory condition
		if (serviceState == ACTIVE_CONNECTIONS){
			// TODO save which connections are active for recreating later when service is restarted
			
			disableAllPlugins();
			for (ConnectedThread t : connections.values()){
				t.cancel();
			}
		}
		unregisterReceiver(receiver);
		cancelNotification();
		
	}
	
	private void shutdownService(boolean disablePlugins){
		if (disablePlugins) 
			disableAllPlugins();
		if (serviceState == NO_CONNECTIONS){
			notifyManager.notify(NOTIFY_ID, 
					getNotification(getString(R.string.service_no_active_connections)));
			Logger.d(TAG, getString(R.string.service_ready_to_shutdown));
			stopSelf();
		}
	}
	
	private void shutdownServiceIfNecessary() {
		if (connections.size() == 0){
			serviceState = NO_CONNECTIONS;
			shutdownService(false);
		}
		else {
			serviceState = ACTIVE_CONNECTIONS;
			notifyManager.notify(NOTIFY_ID, 
					getNotification(getString(R.string.service_active_connections, connections.size())));
		}
	}


	protected void connect(final String address, int deviceType){
		Logger.d(TAG,"address"+address);
		Logger.d(TAG, "type"+deviceType);

		if (address == null) return;
		
		switch(deviceType){
		case Device.BTDEVICE: 	localDevice = LocalDevice.getInstance();
								localDevice.init(this, new ReadyListener() {
									@Override
									public void ready() {
										RemoteDevice device = localDevice.getRemoteForAddr(address);
										localDevice.destroy();
										new ConnectBTThread(device).start();
									}
								});
								break;
		case Device.LANDEVICE:	new ConnectLANThread(address).start();
								break;
		}
			
	}
	
	
	public void disconnect(final String address, int deviceType){
		informPlugins(address, false);
		
		ConnectedThread ct = connections.remove(address);
		if (ct != null)
			ct.cancel();

		// end service if this was the last connection to disconnect
		if (connections.size()==0){
			serviceState = NO_CONNECTIONS;
			shutdownService(true);
		}
		else {
			serviceState = ACTIVE_CONNECTIONS;
			notifyManager.notify(NOTIFY_ID, 
					getNotification(getString(R.string.service_active_connections, connections.size())));
		}
	}
	
	public void sendData(final String address, byte[] data, int deviceType){
		ConnectedThread ct = connections.get(address);
		if (ct != null)
			ct.write(data);
	}
	

	
	private void informPlugins(String address, boolean enable){
		db.open();
		Device device = db.getDevice(address);
		
		if (device != null){
			ArrayList<Event> events = db.fetchEvents(device.id);
			device.events = new HashMap<Integer, Event>();
			
			for (Event e : events){
				if (enable) {
					// remember which plugin was started for which device address
					List<Device> devices = enabledEvents.get(e.pluginId);
					
					if (devices == null) {
						// plugin is not active
						devices = new LinkedList<Device>();
						devices.add(device);
						enabledEvents.put(e.pluginId, devices);
					}
					else {
						// plugin already active, just add the new address
						devices.add(device);
					}
					// add to our fast HashMap for later use when sending data we need fast retrival of pluginId->flag
					device.events.put(e.pluginId, e);
					// start plugin no matter if it was active or not, plugins must be able to handle consecutive start calls
					informPlugIn(e, address, true);
				}
				else {
					// only if this is the last device with a certain event attached, disable the plugin
					List<Device> devices = enabledEvents.get(e.pluginId);
					if (devices != null) {
						if (devices.remove(device)){
							// address found and removed
							if (devices.size()==0){
								enabledEvents.remove(e.pluginId);
								// was the last device which used this plugin, thus disable the plugin now
								informPlugIn(e, address, false);
							}
						}
					}
					else {
						Logger.d(TAG, "disable requested for Plugin " + e.name + " detected, but was never enabled");
						// should not happen, but maybe disconnect was called without ever connecting before
						informPlugIn(e, address, false);
					}
					// normally it shouldn't be any event with this id in device's events map, but we double check
					device.events.remove(e.pluginId);
				}
			}
		}
		db.close();
	}
	
	private void informPlugIn(Event e, String address, boolean enable){
		Logger.d(TAG, (enable ? getString(R.string.enable) : getString(R.string.disable)) + " " + e.name);
		Intent intent;
		if (enable)
			intent = new Intent(AmarinoIntent.ACTION_ENABLE);
		else
			intent = new Intent(AmarinoIntent.ACTION_DISABLE);
		
		intent.putExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS, address);
		intent.putExtra(AmarinoIntent.EXTRA_PLUGIN_ID, e.pluginId);
		intent.putExtra(AmarinoIntent.EXTRA_PLUGIN_SERVICE_CLASS_NAME, e.serviceClassName);
		
		intent.setPackage(e.packageName);
		sendBroadcast(intent);
	}
	
	private void disableAllPlugins(){
		Intent intent = new Intent(AmarinoIntent.ACTION_DISABLE);
		sendBroadcast(intent);
	}
	
	private void broadcastConnectedDevicesList() {
		Intent returnIntent = new Intent(AmarinoIntent.ACTION_CONNECTED_DEVICES);
		if (connections.size() == 0){
			sendBroadcast(returnIntent);
			shutdownService(false);
			return;
		}
		Set<String> addresses = connections.keySet();
		String[] result = new String[addresses.size()];
		result = addresses.toArray(result);
		returnIntent.putExtra(AmarinoIntent.EXTRA_CONNECTED_DEVICE_ADDRESSES, result);
		sendBroadcast(returnIntent);
	}
	
	private void sendConnectionDisconnected(String address){
		String info = getString(R.string.service_disconnected_from, address);
		Logger.d(TAG, info);
		notifyManager.notify(NOTIFY_ID, getNotification(info));
		
		sendBroadcast(new Intent(AmarinoIntent.ACTION_DISCONNECTED)
			.putExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS, address));
		
		broadcastConnectedDevicesList();
	}
	
	private void sendConnectionFailed(String address){
		String info = getString(R.string.service_connection_to_failed, address);
		Logger.d(TAG, info);
		notifyManager.notify(NOTIFY_ID, getNotification(info));
		
		sendBroadcast(new Intent(AmarinoIntent.ACTION_CONNECTION_FAILED)
			.putExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS, address));
	}
	
	private void sendPairingRequested(String address){
		Logger.d(TAG, getString(R.string.service_pairing_request, address));
		sendBroadcast(new Intent(AmarinoIntent.ACTION_PAIRING_REQUESTED)
			.putExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS, address));
	}
	
	private void sendConnectionEstablished(String address){
		String info = getString(R.string.service_connected_to, address);
		Logger.d(TAG, info);

		sendBroadcast(new Intent(AmarinoIntent.ACTION_CONNECTED)
			.putExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS, address));
		
		broadcastConnectedDevicesList();
		
		startForegroundCompat(NOTIFY_ID, 
				getNotification(getString(R.string.service_active_connections, connections.size())));
		
		
	}
	
	
	/* ---------- Binder ---------- */

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}
	
	public class AmarinoServiceBinder extends Binder {
		AmarinoService getService() {
			return AmarinoService.this;
		}
	}
	
	
	
	/* ---------- Notification ---------- */
	
	private void initNotificationManager() {
		notifyManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		
		launchIntent = PendingIntent.getActivity(AmarinoService.this, 0, 
				new Intent(AmarinoService.this, MainScreen.class)
						.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
	}
	
	private Notification getNotification(String title) {
		notification = new Notification(R.drawable.icon_small, title, System.currentTimeMillis());
		notification.flags |= Notification.FLAG_ONGOING_EVENT;
		notification.flags |= Notification.FLAG_NO_CLEAR;
		notification.setLatestEventInfo(this, title, getString(R.string.service_notify_text), launchIntent);
		return notification;
	}
	
	private void cancelNotification(){
		notifyManager.cancel(NOTIFY_ID);
	}
	
	
	/* ---------- Connection Threads ---------- */
	
	private abstract class ConnectThread extends Thread{
		public abstract void run();
		
		public abstract void cancel();
	}
	
	private abstract class ConnectedThread extends Thread{
	    private StringBuffer forwardBuffer = new StringBuffer();
		public final String address;
		public InputStream inStream;
		public OutputStream outStream;
		private HeartbeatThread heartbeat;
		
		public ConnectedThread(String address){
			this.address = address;
		}
		
		/* Call this from the main Activity to establish the connection */
		public void run(){
	        byte[] buffer = new byte[1024];  // buffer store for the stream
	        int bytes = 0; // bytes returned from read()
	        String msg;
	        
	        sendConnectionEstablished(address);
	        
	        // Keep listening to the InputStream until an exception occurs
	        while (true) {
	            try {
	            	// Read from the InputStream
	                bytes = inStream.read(buffer);

	                // Send the obtained bytes to the UI Activity
	                msg = new String(buffer, 0, (bytes != -1) ? bytes : 0 );
	                //Log.d(TAG, msg); // raw data with control flags
	                
	                forwardData(buffer);

	            } catch (IOException e) {
	            	Logger.d(TAG, "communication to " + address + " halted");
	                break;
	            }
	        }
		}
		
		public abstract void cancel();
		
	    /* Call this from the main Activity to send data to the remote device */
		public abstract void write(byte[] bytes);
		
		protected void forwardData(byte[] data){
			String flags = data.split(" ")[0];
			String values = data.split(" ")[1];
			
			//Data type flag should be first char of message
			int dataType = flags.charAt(0);
			//the rest of the message should indicate the number of values
			int numValues = Integer.parseInt(flags.substring(1, flags.length()));
			
			boolean isArray = false;
			if(numValues > 1) isArray = true;
			
			Logger.d(TAG, "Datatype: "+dataType+" - numValues: "+numValues);
			
			char c;
			for (int i=0;i<values.length();i++){
				c = values.charAt(i);
				if (c == MessageBuilder.ARDUINO_MSG_FLAG){
					// TODO this could be used to determine the data type
//					if (i+1<data.length()){
//						int dataType = data.charAt(i+1);
//						i++;
					// depending on the dataType we could convert the following data appropriately
//					}
//					else {
//						// wait for the next char to be sent
//					}
				}
				else if (c == MessageBuilder.ACK_FLAG || c == '#'){
					// message complete send the data
					forwardDataToOtherApps(forwardBuffer.toString(), dataType, isArray);
	            	Logger.d(TAG, "received from "+address+": "+forwardBuffer.toString());
					forwardBuffer = new StringBuffer();
				} else if(c == MessageBuilder.HB_ON_FLAG){
					Intent intent = new Intent(AmarinoIntent.ACTION_HB_ON);
					intent.putExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS, address);
					Logger.d(TAG, "Received HB_ON");
					sendBroadcast(intent);
				} else if(c == MessageBuilder.HB_OFF_FLAG){
					Intent intent = new Intent(AmarinoIntent.ACTION_HB_OFF);
					intent.putExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS, address);
					Logger.d(TAG, "Received HB_OFF");
					sendBroadcast(intent);
				}
				else {
					forwardBuffer.append(c);
				}
			}
		}
		
		protected void forwardDataToOtherApps(String msg, int dataType, boolean isArray){
	    	Logger.d(TAG, "Arduino says: " + msg);
	    	Intent intent = new Intent(AmarinoIntent.ACTION_RECEIVED);
            intent.putExtra(AmarinoIntent.EXTRA_DATA, msg);
            addDataType(intent, dataType, isArray);
            intent.putExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS, address);
            sendBroadcast(intent);
		}
		
		protected void addDataType(Intent intent, int dataType, boolean isArray){
			switch(dataType){
			case MessageBuilder.BOOLEAN_FLAG : 
				if(isArray) intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.BOOLEAN_ARRAY_EXTRA);
				else intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.BOOLEAN_EXTRA);
				break;
			case MessageBuilder.BYTE_FLAG : 
				if(isArray) intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.BYTE_ARRAY_EXTRA);
				else intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.BYTE_EXTRA);
				break;
			case MessageBuilder.CHAR_FLAG : 
				if(isArray) intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.CHAR_ARRAY_EXTRA);
				else intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.CHAR_EXTRA);
				break;
			case MessageBuilder.DOUBLE_FLAG : 
				if(isArray) intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.DOUBLE_ARRAY_EXTRA);
				else intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.DOUBLE_EXTRA);
				break;
			case MessageBuilder.FLOAT_FLAG : 
				if(isArray) intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.FLOAT_ARRAY_EXTRA);
				else intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.FLOAT_EXTRA);
				break;
			case MessageBuilder.INT_FLAG : 
				if(isArray) intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.INT_ARRAY_EXTRA);
				else intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.INT_EXTRA);
				break;
			case MessageBuilder.LONG_FLAG : 
				if(isArray) intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.LONG_ARRAY_EXTRA);
				else intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.LONG_EXTRA);
				break;
			case MessageBuilder.SHORT_FLAG : 
				if(isArray) intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.SHORT_ARRAY_EXTRA);
				else intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.SHORT_EXTRA);
				break;
			case MessageBuilder.STRING_FLAG : 
				if(isArray) intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.STRING_ARRAY_EXTRA);
				else intent.putExtra(AmarinoIntent.EXTRA_DATA_TYPE, AmarinoIntent.STRING_EXTRA);
				break;
			}
			
		}
		
		public String getAddress(){
			return address;
		}

		public void setHeartbeatThread(HeartbeatThread heartbeat) {
			this.heartbeat = heartbeat;
			
		}
		
		public void setInputStream(InputStream in){
			this.inStream = in;
		}
		
		public void setOutputStream(OutputStream out){
			this.outStream = out;
		}
		
		public HeartbeatThread getHeartbeatThread(){
			return heartbeat;
		}
	}
	
	/**
	 * ConnectBTThread tries to establish a connection and starts the communication thread
	 */
	private class ConnectBTThread extends ConnectThread {
		
		//private static final String TAG = "ConnectThread";
		private final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
		
//		private final RemoteDevice mDevice;
		private final RemoteDevice mDevice;
		private BtSocket mSocket;

	    public ConnectBTThread(RemoteDevice device) {
	        mDevice = device;
	    }

	    @Override
	    public void run() {
	      	try {
	      		String info = getString(R.string.service_connecting_to, mDevice.getAddress());
	      		Logger.d(TAG, info);
	      		notifyManager.notify(NOTIFY_ID, getNotification(info));
	      		
	      		boolean isPaired = false;
	      		
	      		try {
	      			isPaired = mDevice.ensurePaired();
	      		}
	      		catch (RuntimeException re){
		      		re.printStackTrace();
		      	}
	      		
	    		if (!isPaired){
	    			//Log.d(TAG, "not paired!");
	    			sendPairingRequested(mDevice.getAddress());
	    			shutdownServiceIfNecessary();
	    		}
	    		else {
	    			//Log.d(TAG, "is paired!");
	    			// Let main thread do some stuff to render UI immediately
		    		Thread.yield();
		    		// Get a BluetoothSocket to connect with the given BluetoothDevice
		    		try {
						mSocket = mDevice.openSocket(SPP_UUID);
					} catch (Exception e) {
						Logger.d(TAG, "Connection via SDP unsuccessful, try to connect via port directly");
						// 1.x Android devices only work this way since SDP was not part of their firmware then
						mSocket = mDevice.openSocket(1);
					}
		    		
		    		// Do work to manage the connection (in a separate thread)
			        manageConnectedSocket(mSocket);
	    		}
			}
	      	
	    	catch (Exception e) {
	    		sendConnectionFailed(mDevice.getAddress());
				e.printStackTrace();
				if (mSocket != null)
					try {
						mSocket.close();
					} catch (IOException e1) {}
					shutdownServiceIfNecessary();
				return;
			}
	    }

	    /** Will cancel an in-progress connection, and close the socket */
	    @SuppressWarnings("unused")
	    @Override
	    public void cancel() {
	        try {
	            if (mSocket != null) mSocket.close();
	            sendConnectionDisconnected(mDevice.getAddress());
	        } 
	        catch (IOException e) { Log.e(TAG, "cannot close socket to " + mDevice.getAddress()); }
	    }
	    
	    protected void manageConnectedSocket(BtSocket socket){
	    	Logger.d(TAG, "connection established BT.");
	    	// pass the socket to a worker thread
	    	String address = mDevice.getAddress();
	    	ConnectedBTThread t = new ConnectedBTThread(socket, address);
	    	connections.put(address, t);
	    	t.start();
	    	
	    	serviceState = ACTIVE_CONNECTIONS;
	    	
	    	// now it is time to enable the plug-ins so that they can use our socket
			informPlugins(address, true);
	    }
	}
	
	/**
	 * ConnectedBTThread is holding the socket for communication with a Bluetooth device
	 */
	private class ConnectedBTThread extends ConnectedThread {
	    private final BtSocket mSocket;
//	    private final InputStream mInStream;
//	    private final OutputStream mOutStream;
//	    private StringBuffer forwardBuffer = new StringBuffer();

	    public ConnectedBTThread(BtSocket socket, String address) {
	    	super(address);
	        mSocket = socket;
	        
//	        InputStream tmpIn = null;
//	        OutputStream tmpOut = null;
	        
	        // Get the input and output streams, using temp objects because
	        // member streams are final
	        try {
	        	inStream = socket.getInputStream();
	        	outStream = socket.getOutputStream();
	        } catch (Exception e) { }
        
//	        inStream = tmpIn;
//	        outStream = tmpOut;
	    }

	    /* Call this from the main Activity to shutdown the connection */
	    @Override
	    public void cancel() {
	        try {
	            mSocket.close();
	            sendConnectionDisconnected(address);
	        } catch (IOException e) { Log.e(TAG, "cannot close socket to " + address); }
	    }
	    
	    /* Call this from the main Activity to send data to the remote device */
		@Override
	    public void write(byte[] bytes){
	        try {
	            outStream.write(bytes);
	            Logger.d(TAG, "send to Arduino: " + new String(bytes));
	        } catch (IOException e) { }
		}
	}

	/**
	 * ConnectLANThread tries to establish a connection and starts the communication thread
	 */
	private class ConnectLANThread extends ConnectThread {
		private Socket socket;
		private String address;
		
		public ConnectLANThread(String address) {
			this.address = address;
		}

		@Override
		public void run() {
			try {
				socket = new Socket(InetAddress.getByName(address), 80);
			} catch (UnknownHostException e) {
				Logger.d(TAG, "Unknown Host: "+ address);

			} catch (IOException e) {
				Logger.d(TAG, "IO Exception: "+ address);			
				
			} catch (Exception e) {
				Logger.d(TAG, "Could not initialize Socket to "+ address);
			}
			
    		// Do work to manage the connection (in a separate thread)
	        manageConnectedSocket(socket);
		}

		@Override
		public void cancel() {
	        try {
	            if (socket != null) socket.close();
	            sendConnectionDisconnected(address);
	        } 
	        catch (IOException e) { Log.e(TAG, "cannot close socket to " + address); }		
		}
		
	    protected void manageConnectedSocket(Socket socket){
	    	Logger.d(TAG, "connection established LAN.");
	    	// pass the socket to a worker thread
	    	ConnectedLANThread t = new ConnectedLANThread(socket, address);
	    	connections.put(address, t);
	    	t.start();
	    	
	    	serviceState = ACTIVE_CONNECTIONS;
	    	
	    	// now it is time to enable the plug-ins so that they can use our socket
			informPlugins(address, true);
	    }
	}
	
	/**
	 * ConnectedLANThread is holding the socket for communication with a LAN device
	 */
	private class ConnectedLANThread extends ConnectedThread {
		private Socket mSocket;

		public ConnectedLANThread(Socket socket, String address){
			super(address);
			mSocket = socket;
			
//			Logger.d(TAG,"is socket bind"+mSocket.isBound());
			
//	        InputStream tmpIn = null;
//	        OutputStream tmpOut = null;
//	        
	        // Get the input and output streams, using temp objects because
	        // member streams are final
	        try {
	        	setInputStream(mSocket.getInputStream());
	        	setOutputStream(mSocket.getOutputStream());
	        } catch (Exception e) { Log.e(TAG, "problem creating in/outPutStream: " + e);}

//	        outStream = tmpOut;
//	        inStream = tmpIn;
		}

	    /* Call this from the main Activity to send data to the remote device */
		@Override
		public void write(byte[] bytes){
	        try {
	        	outStream.write(bytes);
	        	outStream.flush();
	            Logger.d(TAG, "send to Arduino: " + new String(bytes));
	        } catch (IOException e) { }
		}
		
		@Override
		public void cancel() {
	        try {
	            mSocket.close();
	            sendConnectionDisconnected(address);
	        } catch (IOException e) { Log.e(TAG, "cannot close socket to " + address); }
		}
	}
	
	private class HeartbeatThread extends Thread{
		private OutputStream outStream;
		private ConnectedThread ct;
		
		private static final int TIMEOUT_THRESHHOLD = 2;
		
		private boolean stop = false;
		
		public HeartbeatThread(ConnectedThread ct){
			this.outStream = ct.outStream;
			this.ct = ct;
			
			Logger.d(TAG, "Heartbeat startet for "+ ct.getAddress());
		}
		
		public void run(){
			int timeouts = 0;
			while(!stop){
				String message = MessageBuilder.ALIVE_MSG;
		        Logger.d(TAG, "Heartbeat Started");
				try {
					outStream.write(message.getBytes("ISO-8859-1"));
				} catch (UnsupportedEncodingException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					timeouts++;
					Logger.d(TAG, "Heartbeat timed out ("+timeouts+"). Threshhold is "+TIMEOUT_THRESHHOLD);

					if(timeouts >= TIMEOUT_THRESHHOLD){
						Logger.d(TAG, "Heartbeat Thresshold reached for "+ ct.getAddress() +", connection lost");
						this.stopp();
					}
				} catch (Exception e) {
					Logger.d(TAG, "General Exception");
				}
					
				
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
		public void stopp(){
			Intent intent = new Intent(AmarinoIntent.ACTION_HB_TIMEOUT);
			intent.putExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS, ct.address);
	        sendBroadcast(intent);
	        
			Logger.d(TAG, "Heartbeat Stopped");
			
	        this.stop = true;
			ct.cancel();
		}
	}
	
	/* ---------- BroadcastReceiver ---------- */
	
	BroadcastReceiver receiver = new BroadcastReceiver() {
		
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (action == null) return;
			//Log.d(TAG, action);
			
			if (AmarinoIntent.ACTION_SEND.equals(action)){
				intent.setClass(context, AmarinoService.class);
				startService(intent);
			}
			
			if (AmarinoIntent.ACTION_HB_ON.equals(action)){
				String address = intent.getStringExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS);
				ConnectedThread ct = connections.get(address);

		        HeartbeatThread heartbeat = new HeartbeatThread(ct);
		        ct.setHeartbeatThread(heartbeat);
		        
		        heartbeat.start();
			}
			
			if (AmarinoIntent.ACTION_HB_OFF.equals(action)){
				String address = intent.getStringExtra(AmarinoIntent.EXTRA_DEVICE_ADDRESS);
				ConnectedThread ct = connections.get(address);
				
				HeartbeatThread heartbeat = ct.getHeartbeatThread();
				heartbeat.stopp();
			}
		}
	};
	
	
	
	/* ---------- use setForeground() but be also backward compatible ---------- */
	
	@SuppressWarnings("unchecked")
	private static final Class[] mStartForegroundSignature = new Class[] {
        int.class, Notification.class};
    @SuppressWarnings("unchecked")
	private static final Class[] mStopForegroundSignature = new Class[] {
        boolean.class};
    
    private Method mStartForeground;
    private Method mStopForeground;
    private Object[] mStartForegroundArgs = new Object[2];
    private Object[] mStopForegroundArgs = new Object[1];
	
	
	/**
     * This is a wrapper around the new startForeground method, using the older
     * APIs if it is not available.
     */
    void startForegroundCompat(int id, Notification notification) {
        // If we have the new startForeground API, then use it.
        if (mStartForeground != null) {
            mStartForegroundArgs[0] = Integer.valueOf(id);
            mStartForegroundArgs[1] = notification;
            try {
                mStartForeground.invoke(this, mStartForegroundArgs);
            } catch (InvocationTargetException e) {
                // Should not happen.
                Log.w("MyApp", "Unable to invoke startForeground", e);
            } catch (IllegalAccessException e) {
                // Should not happen.
                Log.w("MyApp", "Unable to invoke startForeground", e);
            }
            return;
        }
        
        // Fall back on the old API.
        setForeground(true);
        notifyManager.notify(id, notification);
    }
    
    /**
     * This is a wrapper around the new stopForeground method, using the older
     * APIs if it is not available.
     */
    void stopForegroundCompat(int id) {
        // If we have the new stopForeground API, then use it.
        if (mStopForeground != null) {
            mStopForegroundArgs[0] = Boolean.TRUE;
            try {
                mStopForeground.invoke(this, mStopForegroundArgs);
            } catch (InvocationTargetException e) {
                // Should not happen.
                Log.w("MyApp", "Unable to invoke stopForeground", e);
            } catch (IllegalAccessException e) {
                // Should not happen.
                Log.w("MyApp", "Unable to invoke stopForeground", e);
            }
            return;
        }
        
        // Fall back on the old API.  Note to cancel BEFORE changing the
        // foreground state, since we could be killed at that point.
        cancelNotification();
        setForeground(false);
    }
}