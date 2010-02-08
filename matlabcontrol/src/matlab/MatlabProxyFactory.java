package matlab;

import java.io.File;
import java.io.IOException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.Vector;

/**
 * Use this class to create instances of MatlabProxy. Each proxy will control
 * a different instance of MATLAB.
 * 
 * Confirmed to work on OS X, Windows, & Linux. If your operating system is
 * not reported as OS X, it will launch it as if it were on Windows or Linux
 * (they are handled exactly the same).
 * 
 * @see MatlabProxyController
 * 
 * @author <a href="mailto:jak2@cs.brown.edu">Joshua Kaplan</a>
 */
public class MatlabProxyFactory
{
	/**
	 * A timer that keeps periodically checks if the proxies are still
	 * connected.
	 */
	private Timer _connectionTimer;
	
	/**
	 * Listeners for when connections are established and lost.
	 * 
	 * @see MatlabProxyFactory#addConnectionListener(MatlabConnectionListener)
	 * @see MatlabProxyFactory#removeConnectionListener(MatlabConnectionListener)
	 */
	private Vector<MatlabConnectionListener> _listeners = new Vector<MatlabConnectionListener>();
	
	/**
	 * Specified location of MATLAB executable. If none is ever provided then
	 * an OS specific value is used.
	 * 
	 * @see MatlabProxyFactory#setMatlabLocation(String)
	 */
	private String _specifiedMatlabLoc = null;
	
	/**
	 * Time to wait for MatlabInternalProxy to be received. Default is 60 seconds.
	 */
	private int _timeout = 60000;
	
	/**
	 * Map of bindings to MatlabProxy instances.
	 */
	private Map<String, MatlabProxy> _proxies = new HashMap<String, MatlabProxy>();
	
	/**
	 * The receivers waiting for a proxy to be sent to them.
	 */
	private Vector<MatlabProxyReceiver> _receivers = new Vector<MatlabProxyReceiver>();

	/**
	 * The RMI registry used to communicate between JVMs. There is only ever
	 * one registry actually running.
	 */
	private static Registry _registry = null;
	
	/**
	 * Generates a random binding value.
	 * 
	 * @return
	 */
	private static String getRandomBindValue()
	{
		return UUID.randomUUID().toString();
	}
	
	/**
	 * This class receives the MatlabProxy from the MATLAB JVM. This private
	 * inner class is used to hide the registerControl(...) method which must
	 * be public to work with RMI, but there's no need for anyone to directly
	 * call this method.
	 */
	private class MatlabProxyReceiver implements MatlabInternalProxyReceiver
	{
		/**
		 * This method is to be called by MatlabServer instance running inside of
		 * the MATLAB JVM.
		 * 
		 * @param controller
		 */
		public void registerControl(String bindValue, MatlabInternalProxy internalProxy)
		{
			MatlabProxy proxy = new MatlabProxy(internalProxy);
			_proxies.put(bindValue, proxy);
			MatlabProxyFactory.this.connectionEstablished(proxy);
			
			//Allow this receiver to get garbage collected
			_receivers.remove(this);
		}
	}
	
	/**
	 * Set the location of the MATLAB program. If this property is not set an
	 * appropriate default for your operating system will be used. If that
	 * fails, then use this method to give the correct location.
	 * 
	 * @param matlabLocation
	 */
	public void setMatlabLocation(String matlabLocation)
	{
		_specifiedMatlabLoc = matlabLocation;
	}
	
	/**
	 * Requests a proxy. When the proxy has been made (there is a possibility
	 * it will not be if errors occur), all listeners will be notified.
	 * 
	 * @see MatlabProxyFactory#getProxy()
	 * @see MatlabProxyFactory#addConnectionListener(MatlabConnectionListener)
	 * 
	 * @throws MatlabConnectionException
	 */
	public void requestProxy() throws MatlabConnectionException
	{
		this.requestProxy(getRandomBindValue());
	}
	
	/**
	 * Internal method that actually sets up the RMI binding and begins the
	 * process of creating the proxy.
	 * 
	 * @param bindValue unique binding used to register the proxy control
	 * @throws MatlabConnectionException
	 */
	private void requestProxy(String bindValue) throws MatlabConnectionException
	{
		//If there is no timer yet, create a timer to monitor the connection
		if(_connectionTimer == null)
		{
			_connectionTimer = new Timer();
			_connectionTimer.schedule(new TimerTask()
			{
				public void run()
				{
					MatlabProxyFactory.this.checkConnections();
				}				
			}, 5000, 1000);
		}
		
		//Initialize the registry if necessary
		initRegistry();

		//If a registry exists, bind this class and then launch Matlab which will in turn launch the MatlabControl
		if(_registry != null)
		{
			//Bind this object
			try
			{
				MatlabProxyReceiver receiver = new MatlabProxyReceiver();
				_receivers.add(receiver);
				_registry.rebind(bindValue, UnicastRemoteObject.exportObject(receiver, 0));
			}
			catch(Exception e)
			{
				throw new MatlabConnectionException("Could not bind proxy receiever to the RMI registry", e);
			}
			
			//Run Matlab
			this.runMatlab(bindValue);
		}
		else
		{
			throw new MatlabConnectionException("Could not create or connect to the RMI registry.");
		}
	}
	
	/**
	 * Initializes the registry if it has not already been set up. Specifies
	 * the code base so that paths with spaces in them will work properly.
	 * 
	 * @throws MatlabConnectionException
	 */
	private static void initRegistry() throws MatlabConnectionException
	{
		//If the registry hasn't been created
		if(_registry == null)
		{
			//Create a RMI registry
			try
			{
	    		_registry = LocateRegistry.createRegistry(Registry.REGISTRY_PORT);
			}
			//If we can't create one, try to retrieve an existing one
			catch (Exception e)
			{
				try
				{
					_registry = LocateRegistry.getRegistry();
				}
				catch(Exception ex)
				{
					throw new MatlabConnectionException("Could not create or connect to the RMI registry", ex);
				}
			}
			
			//If we created a registry, register this code base
			if(_registry == null)
			{
				//Get the location of the directory or jar this class is in
				String path = MatlabProxyFactory.class.getProtectionDomain().getCodeSource().getLocation().getPath();
				
				//Tell the code base where it is, and just to be safe force it to use it
				//(This is necessary so that paths with spaces work properly)
				System.setProperty("java.rmi.server.codebase", "file://"+path);
				System.setProperty("java.rmi.server.useCodebaseOnly", "true");
			}
		}
	}
	
	/**
	 * Calling this method will get the MatlabProxy. This will take some
	 * time as it involves launching MATLAB. If for any reason a connection
	 * cannot be established, this method will timeout in 60 seconds or the
	 * amount of time as specified by setTimeout(...)
	 * 
	 * @see MatlabProxyFactory#requestProxy()
	 * 
	 * @throws MatlabConnectionException
	 * 
	 * @return proxy
	 */
	public MatlabProxy getProxy() throws MatlabConnectionException
	{
		String bindValue = getRandomBindValue();
		this.requestProxy(bindValue);
		
		//Wait until the controller is received or until timeout
		long timeout = System.currentTimeMillis() + _timeout;
		while(!_proxies.containsKey(bindValue) && System.currentTimeMillis() < timeout)
		{
			try
			{
				Thread.sleep(1000);
			}
			catch (InterruptedException e)
			{
				throw new MatlabConnectionException("Thread was interrupted while waiting for MATLAB control", e);
			}
		}
		
		if(!_proxies.containsKey(bindValue))
		{
			throw new MatlabConnectionException("MATLAB control could not be created");
		}
		
		return _proxies.get(bindValue);
	}
	
	/**
	 * Sets the maximum amount of time to wait in attempting to setup a
	 * connection to MATLAB in milliseconds. The default value is 60
	 * seconds.
	 * 
	 * @param ms
	 */
	public void setTimeout(int ms)
	{
		_timeout = ms;
	}
	
	/**
	 * Launches Matlab. This is OS specific.
	 * Confirmed to work on OS X, Windows, & Linux.
	 * 
	 * @param bindValue binding value used to send the proxy to this JVM
	 */
	private void runMatlab(String bindValue) throws MatlabConnectionException
	{
		//Get the location of the directory or jar this class is in
		String path = this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
		
		//Operating system
		String osName = System.getProperty("os.name");
		
		//Determine the location of MATLAB, 
		String matlabLoc = "";
		if(_specifiedMatlabLoc == null)
		{
			//OS X
			if(osName.equalsIgnoreCase("Mac OS X"))
			{
				matlabLoc = this.getOSXMatlabLocation();
			}
			//Windows, Linux, and possibly others
			else
			{
				matlabLoc = "matlab";
			}
		}
		else
		{
			matlabLoc = _specifiedMatlabLoc;
		}
		
		//Argument that MATLAB will run on start. It tells it to add this code to it's classpath, create the
		//MatlabServer class, and then to clear everything that just happened so its not visible to the user
		String runArg = "javaaddpath '" + path.replace("%20", " ") + "'; server = matlab.MatlabServer('" + bindValue + "'); clear";
		
		//Attempt to run MATLAB
		try
		{
			Runtime.getRuntime().exec(new String[]{matlabLoc, "-desktop", "-r", runArg});
		}
		catch (IOException e)
		{
			throw new MatlabConnectionException("Could not launch MATLAB. OS is believed to be: " + osName, e);
		}
	}
	
	private String getOSXMatlabLocation() throws MatlabConnectionException
	{
		String matlabName = null;
		for(String fileName : new File("/Applications/").list())
		{
			if(fileName.startsWith("MATLAB") && fileName.endsWith(".app"))
			{
				matlabName = fileName;
			}
		}
		
		if(matlabName == null)
		{
			throw new MatlabConnectionException("Could not find MATLAB location, please specify one using setMatlabLocation(...)");
		}
		
		return  "/Applications/"+matlabName+"/bin/matlab";
	}
	
	/**
	 * Add a listener to be notified when MATLAB connections are established
	 * and lost.
	 * 
	 * @param listener
	 */
	public void addConnectionListener(MatlabConnectionListener listener)
	{
		_listeners.add(listener);
	}
	
	/**
	 * Removes a listener so that it is no longer notified.
	 * 
	 * @param listener
	 */
	public void removeConnectionListener(MatlabConnectionListener listener)
	{
		_listeners.remove(listener);
	}
	
	/**
	 * Called when a connection has been established.
	 * 
	 * @param proxy
	 */
	private void connectionEstablished(MatlabProxy proxy)
	{
		for(MatlabConnectionListener listener : _listeners)
		{
			listener.connectionEstablished(proxy);
		}
	}
	
	/**
	 * Called when it detects a connection has been lost.
	 */
	private void connectionLost(MatlabProxy proxy)
	{
		for(MatlabConnectionListener listener : _listeners)
		{
			listener.connectionLost(proxy);
		}
	}
	
	/**
	 * Checks the connections to MATLAB. If a connection has died, the
	 * listeners are informed and all references to it by this class
	 * are removed.
	 */
	private void checkConnections()
	{
		synchronized(_proxies)
		{
			//Check each proxy's connection, if it has died add to toRemove
			Vector<String> toRemove = new Vector<String>();
			for(String proxyKey : _proxies.keySet())
			{
				MatlabProxy proxy = _proxies.get(proxyKey);
				if(!proxy.isConnected())
				{
					toRemove.add(proxyKey);
					this.connectionLost(proxy);
				}
			}
			
			//Remove the dead connections
			for(String proxyKey : toRemove)
			{
				_proxies.remove(proxyKey);
			}
		}
	}
}