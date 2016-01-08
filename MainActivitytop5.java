package edu.northwestern.xfinity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

import com.google.gson.Gson;

import edu.northwestern.aqualab.alice.Local;
import edu.northwestern.aqualab.alice.Remote;
import edu.northwestern.aqualab.alice.active.HTTPModule.HTTPProbeResponse;
import edu.northwestern.aqualab.alice.active.IPerfModule;
import edu.northwestern.aqualab.alice.active.NDTModule;
import edu.northwestern.aqualab.alice.active.HTTPModule;
import edu.northwestern.aqualab.alice.active.WifiScanModule;
import edu.northwestern.aqualab.alice.active.IPerfModule.IPerfResponse;
import edu.northwestern.aqualab.alice.active.WifiScanModule.WifiScanResponse;
import edu.northwestern.aqualab.alice.receivers.WifiScanResult;
import android.support.v7.app.ActionBarActivity;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.Camera.PreviewCallback;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Vibrator;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends ActionBarActivity implements LocationListener {

	public static final String EXPERIMENT_LOG_FILE = "results.txt";
	public static final String LOCATION_LOG_FILE = "location.txt";
	
	Button experimentButton;
	Button authSuccessfulButton;
	Button authFailedButton;
	
	TextView debugTextView;
	TextView statusTextView;
	LocationManager mLocationManager;
	Location last_location;
	
	ExperimentState experiment_state;
	
	XfinityExperimentResult currentResult;
	Thread experimentThread;
	Boolean authenticated = null;
	
	WakeLock wakeLock;
	
	File location_file;
	
	WebView mWebView;
	
	Map<String, Boolean> completedAccessPoints; // = new HashMap<String, Boolean>();
	
	public enum ExperimentState { STARTED, PAUSED, COMPLETED, IN_ERROR, CREATED };
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		Log.d("SavedState", "In onCreate()");
		
		experiment_state = ExperimentState.CREATED;
		
		experimentButton = (Button)findViewById(R.id.experimentButton);
		experimentButton.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				toggleExperiment();				
			}
		});
		
		authSuccessfulButton = (Button)findViewById(R.id.authSuccessfulButton);
		authSuccessfulButton.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				appendDebug("Authentication successful");
				authenticated = true;
				
			}
		});
		
		authFailedButton = (Button)findViewById(R.id.authFailedButton);
		authFailedButton.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				appendDebug("authentication failed");
				authenticated = false;				
			}
		});
		
		completedAccessPoints = new HashMap<String, Boolean>();
		
		//restore saved state -- if applicable
		if(savedInstanceState != null) {
			String[] visited_aps = savedInstanceState.getStringArray("visited_aps");
			//re-init map
			for (String ap : visited_aps) {
				completedAccessPoints.put(ap, true);
			}
			
			Log.d("SavedState", (new Gson()).toJson(completedAccessPoints));
			
		}
		
		statusTextView = (TextView)findViewById(R.id.statusTextView);
		debugTextView = (TextView)findViewById(R.id.debugTextView);
		
		//Using both network and GPS for maximum effectiveness.
		mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, this);
		mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1, this);
		
		mWebView = new WebView(this);//(WebView)findViewById(R.id.xfinityWebView);
		WebSettings mWebViewSettings = mWebView.getSettings();
		mWebViewSettings.setJavaScriptEnabled(true);
		
	}

	public void toggleExperiment() {
		
		if (experiment_state == ExperimentState.CREATED) {
			
			//start the experiments
			experimentThread = new Thread() {
				@Override
				public void run() {
					startNewExperiment();
				};
			};
			
			experimentThread.start();
			experiment_state = ExperimentState.STARTED;
			experimentButton.setText("Stop Experiment");
			
		} else if (experiment_state == ExperimentState.STARTED) {
			//experiment is running - pause the experiment
			experimentThread.interrupt();
			experiment_state = ExperimentState.PAUSED;
			experimentButton.setText("Start Experiment");
			wakeLock.release();
			
			//pauseExperiment() //i think this is just stopExperiment -- kill experimentThread;
		} else if (experiment_state == ExperimentState.PAUSED) {
			//start the experiments
			experimentThread = new Thread() {
				@Override
				public void run() {
					startNewExperiment();
				};
			};
			
			experimentThread.start();
			experiment_state = ExperimentState.STARTED;
		} else if (experiment_state == ExperimentState.COMPLETED) {
			//experiment is finished - do nothing (this doesn't seem right)
			//start the experiments
			experimentThread = new Thread() {
				@Override
				public void run() {
					startNewExperiment();
				};
			};
			
			experimentThread.start();
			experiment_state = ExperimentState.STARTED;
			experimentButton.setText("Stop Experiment");
		}
		
	}
	
	public void setStatus(final String msg) {
		
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				statusTextView.setText(msg);				
			}
		});
		
	}
	
	public void appendDebug(final String msg) {
		
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				debugTextView.append(msg + "\n");				
			}
		});
	}
	
	/**
	 * Each experiment consists of the following:
	 *   - a gps recording (or enabling location listeners)
	 *   - running NDT to closest (Chicago) server.
	 *   - running iPerf to Northwestern servers.
	 *   - ** not implemented ** (A speedtest run) -- not sure if we need to do this. Check if we are using speed tests in the paper
	 *   
	 */
	public void startNewExperiment() {
		
		setStatus("Starting Experiment...");
		
		//get partial wake lock
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "");
        //PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "");
        wakeLock.acquire();
		
		for (;;) {
			
			//sleep for 5 seconds just because
			try {
				Thread.sleep(5000);
			} catch (InterruptedException ie) {
				//do nothing
			}
			
			currentResult = new XfinityExperimentResult();
			currentResult.startLocation = last_location;
			currentResult.starttime = System.currentTimeMillis();
			
			//check wifi and turn on
			WifiManager wm = (WifiManager)getSystemService(Context.WIFI_SERVICE);
			if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
			
			//add latest scan to 
			WifiScanResponse wifi_scan_result = conductWifiScan();
			currentResult.scan_result = wifi_scan_result;
			
			appendDebug("Wifi Scan Completed - " + currentResult.scan_result.scan_results.size() + " APs Found");
			setStatus("Wifi Scan Complete");
			
			//iterate through sorted list to find un-attempted access points -- sorts in place
			Collections.sort(wifi_scan_result.scan_results, new Comparator<WifiScanResult>() {
				@Override
				public int compare(WifiScanResult lhs, WifiScanResult rhs) {
					// TODO Auto-generated method stub
					return (rhs.level - lhs.level);
				}
			}); 
			WifiScanResult selectedAccessPoint = null;
			boolean isbestXfinityAP = true;
			int countxfinity = 0;
			int countxfinity5 = 0;
			int countxfinity2 = 0;
			for (WifiScanResult wsr : wifi_scan_result.scan_results) {
				//we only care about xfinitywifi
				
				if (!wsr.SSID.equals("xfinitywifi")) continue;
				if (completedAccessPoints.containsKey(wsr.BSSID)) continue;				
				countxfinity += 1;
				
				if (countxfinity > 5 && countxfinity2 != 0 && countxfinity5 != 0) break;
				else if (countxfinity > 5 && countxfinity2 != 0 && wsr.frequency < 5000) continue;
				else if (countxfinity > 5 && countxfinity5 != 0 && wsr.frequency > 5000) continue;
				
				if (wsr.frequency > 5000) countxfinity5 += 1;
				else countxfinity2 += 1;
				
				appendDebug("\n"+Integer.toString(countxfinity)+" Found xfinitywifi AP: " + wsr.BSSID + "with signal: "+Integer.toString(wsr.level) + " frequency:"+Integer.toString(wsr.frequency)+ (System.currentTimeMillis()/1000));
				selectedAccessPoint = wsr;
				
				//run the experiments here
				//this is not the best structure -- to be perfectly honest...
				boolean ret = connectToAccessPoint(selectedAccessPoint);			
				if (!ret) {
					
					wm.disconnect();
					appendDebug("Unable to connect.");
					continue;
				}
				appendDebug("Connected to xfinitywifi AP:"+wsr.BSSID);				
				currentResult.available_bssids.add(selectedAccessPoint.BSSID);
				if ( !isbestXfinityAP){
					wm.disconnect();
					continue;				
				}
				completedAccessPoints.put(selectedAccessPoint.BSSID, true);	
				authenticated = true;
				isbestXfinityAP = false;
				//Vibrate to indicate the need for authentication
				Vibrator vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
				vibrator.vibrate(2000);
				//block until successful or unsuccessful
				/*
				while(authenticated == null) {
					appendDebug("Waiting on authentication: " + String.valueOf(authenticated));
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				*/
				//appendDebug("Authentication Successful");
				if (authenticated) {
					currentResult.measured_bssid = selectedAccessPoint.BSSID;
					//now run the bandwidth tests
					runBandwidthTests();
					wm.disconnect();
					//appendDebug("back to scan.... "+ currentResult.connected_bssid);
					
				} else {
					//log the authentication error
					//currentResult.error = "Error authenticating XfinityWifi";
					wm.disconnect();
					
					//currentResult.endLocation = last_location;
					//currentResult.endtime = System.currentTimeMillis();
					
					//appendDebug((new Gson()).toJson(currentResult));
					
					//Local.appendExternalFileFromString("xfinity", EXPERIMENT_LOG_FILE, (new Gson()).toJson(currentResult), this);
					
				}
				
				vibrator.vibrate(1000);
				
			}
			
			currentResult.endLocation = last_location;
			currentResult.endtime = System.currentTimeMillis();
			WifiScanResponse wifi_scan_result2 = conductWifiScan();
			currentResult.end_scan_result = wifi_scan_result2;
			
			
			String json_result = (new Gson()).toJson(currentResult);
			//appendDebug("\nWriting to file:"+ currentResult.measured_bssid + currentResult.available_bssids.toString() );
			//try{
			//	appendDebug("\\nNDT results written: "+ currentResult.ndt.toString());
			//}catch(Exception e)
			//{
			//	appendDebug("Problem with NDT");
			//}
			
			
			//appendDebug("\nAll results written:"+json_result);	
			Local.appendExternalFileFromString("xfinity", EXPERIMENT_LOG_FILE, json_result, this);
			continue;

		} //end of event loop
		
	}
	
	public boolean connectToXfinityWifiNetwork(String bssid) {
		
		appendDebug("Attempting to connect to XfinityWifi: " + bssid);
		WifiConfiguration wifiConfig = new WifiConfiguration();
		wifiConfig.BSSID = bssid;
		wifiConfig.priority = 1;
		wifiConfig.allowedKeyManagement.set(KeyMgmt.NONE);
		wifiConfig.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
		wifiConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
		wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
		wifiConfig.status=WifiConfiguration.Status.ENABLED;

		ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
		WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		int netId = wifi.addNetwork(wifiConfig);
		
		if (netId == -1) return false;
		wifi.enableNetwork(netId, true);
		
		//wait for connect signal
		long timeout_length = 15000; //ms
		boolean successful_connection = false;
		try {
			//SHITTY: loop through with sleep for finish
			for(int i=0; i < timeout_length/500; i++) {
				
				NetworkInfo ni = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
				if (ni.isConnected()) {
					WifiInfo wi = wifi.getConnectionInfo();
					
					if (wi.getBSSID().equals(bssid)) {
						successful_connection = true;
						break;
					}
				}
				
				Thread.sleep(500);
			}
		} catch (InterruptedException ie) {
			//something bad happened
			//don't do anythign though
		}
		
		return successful_connection;		
	}
	
	
	public boolean connectToAccessPoint(WifiScanResult ap) {
		
		if (ap == null) return false;
		
		setStatus("Connecting to Access Point: " + ap.BSSID);
		WifiManager wm = (WifiManager)getSystemService(Context.WIFI_SERVICE);
		
		//disconnect from any current APs
		wm.disconnect();
		//try {
		//	Thread.sleep(500);
		//} catch (InterruptedException ie) {
		//	//do nothing
		//}
		currentResult.connected_bssid = ap.BSSID;
		currentResult.attempted_bssids.add(ap.BSSID);
		boolean ret = connectToXfinityWifiNetwork(ap.BSSID);
		
		//unable to connect to xfinity wifi
		if (ret == false) {
			//Log an unsuccessful connection
			//TODO
			currentResult.connection_established = false;
			return false;
		}
		
		currentResult.connection_established = true;
		
		return true;
		
	}
	
	public boolean checkConnection() {
		
//		this.runOnUiThread(new Runnable() {
//			
//			@Override
//			public void run() {
		
		//wifi is connected to correct xfinitywifi hotspot -- assuming
		//now do the web login -- not sure exactly how though...
		mWebView.loadUrl("http://wifilogin.comcast.net/wifi/xwifi.php"); //test for redirection
		
		String js_string = "<script> document.getElementById(\"username\").value = \"john_rula\"; document.getElementById(\"password\").value = FullHand3; document.getElementById(\"sign_in\").submit(); </script>";
		mWebView.loadData(js_string, "text/html", "utf-8");
				
//			}
//			
//		});
		
		boolean connected = false;
		ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
		
		//waitfor siginal
		int sleep_count = 0;
		while(!connected) {
			//need to check for route to host
			//do I need a landmark server on hinckley (echo?)
			String res = Remote.getString("http://config.aqualab.cs.northwestern.edu/data/alice/connect_test");
			if (res == null || !res.equals("true")) {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				connected = true;
				break;
			}
			
			if (sleep_count >= 5) {
				break;
			}
			sleep_count++;
		}
		
		if (!connected) {
			currentResult.error = "Unable to log in with comcast credentials";
			return false;
		}
		
		//TODO some sort of automatic connecting
		//not sure what though -- browser scripting -- can we do that with webview? (yes?)
		
		//run tests
		return connected;
	}

	public boolean runBandwidthTests() {
		
		//first run iperf throughput tests
		//Trying to send message to northwestern to show availability
		appendDebug("Sending HTTP GET to Northwestern");
		HTTPModule http = new HTTPModule(this,null, null, null);
		JSONObject argt = new JSONObject();
		try {
			argt.put("port", 6000);
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		HTTPProbeResponse http_response = http.doHTTP("165.124.182.238", "","Xfinity", argt);
		currentResult.http = http_response;
		appendDebug("Running IPerf Tests");
		IPerfModule iperf = new IPerfModule(this, "iperf", null, null, " -P 1 -i 0.5 -f k -t 10", "syrah.cs.northwestern.edu", 8282);
		IPerfResponse iperf_response = iperf.doIPerf("");
		currentResult.iperf = iperf_response;
		
		appendDebug("Running NDT Tests");
		//now run an NDT test -- problem with NDT is that callbacks are asynchronous for some reason
		//spin wait for NDT to finish ... yup ...
		NDTModule ndt = new NDTModule(this, "ndt", null, null);
		Map<String, Object> ndtResult = ndt.run();  //really hope this blocks...
		currentResult.ndt = ndtResult;
		
		//no speed test for now
		
		//log successful results
		String json_result = (new Gson()).toJson(currentResult);
		appendDebug(json_result);
		//Local.appendExternalFileFromString("xfinity", EXPERIMENT_LOG_FILE, json_result, this);
		
		//reset results
		//currentResult = new XfinityExperimentResult();
		
		return true;
		
	}
	
	public WifiScanResponse conductWifiScan() {
		WifiScanModule wsm = new WifiScanModule(this, null, null, null);
		WifiScanResponse wsr = wsm.doWifiScan();	
		
		return wsr;
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	
	//Location Listener Required Methods
	@Override
	public void onLocationChanged(Location location) {
		// TODO Auto-generated method stub
		last_location = location;
		//Log locations to file
		Local.appendExternalFileFromString("xfinity", LOCATION_LOG_FILE, (new Gson()).toJson(location), this);		
		
	}

	@Override
	public void onProviderDisabled(String provider) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onProviderEnabled(String provider) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
		// TODO Auto-generated method stub
		
	}
	
	//END Location Listener Required Methods
	
	//Lifecycle Methods
	
	@Override
	protected void onSaveInstanceState(Bundle outState) {
		
		Log.d("SavedState", "In onSaveInstanceState");
		Log.d("SavedState", (new Gson()).toJson(completedAccessPoints));
		
		//save string of visited bssids
		Set<String> keyset = completedAccessPoints.keySet();
		String[] visited_aps = new String[keyset.size()];
		visited_aps = keyset.toArray(visited_aps);
		
		outState.putStringArray("visited_aps", visited_aps);
		super.onSaveInstanceState(outState);
	}
	
	@Override
	protected void onResume() {
		// TODO Auto-generated method stub
		super.onResume();
		
		//reload visited_access_points and currentXfinityResult
		
	}
	
	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
		
		//serialize visited_access_points and current XfinityResult
		
	}
	
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		// TODO Auto-generated method stub
		super.onConfigurationChanged(newConfig);
		
		//do nothing
	}
	
	//END Lifecycle Methods
	
	public class XfinityExperimentResult {
		
		public Map<String, Object> ndt;
		public IPerfResponse iperf;
		public HTTPProbeResponse http;
		public Location startLocation;
		public Location endLocation;
		public long starttime;
		public long endtime;
		public String measured_bssid;
		
		public WifiScanResponse scan_result;
		public WifiScanResponse end_scan_result;
		
		public String connected_bssid;
		public ArrayList<String> available_bssids = new ArrayList<String>();
		public ArrayList<String> attempted_bssids = new ArrayList<String>();
		public boolean test_successful;
		public boolean connection_established;
		public String error;
	}
}
