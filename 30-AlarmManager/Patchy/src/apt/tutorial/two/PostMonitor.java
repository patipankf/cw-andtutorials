package apt.tutorial.two;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import winterwell.jtwitter.Twitter;
import apt.tutorial.IPostListener;
import apt.tutorial.IPostMonitor;
import com.commonsware.cwac.wakeful.WakefulIntentService;

public class PostMonitor extends WakefulIntentService {
	public static final int NOTIFICATION_ID=1337;
	public static final String STATUS_UPDATE="apt.tutorial.three.STATUS_UPDATE";
	public static final String FRIEND="apt.tutorial.three.FRIEND";
	public static final String STATUS="apt.tutorial.three.STATUS";
	public static final String CREATED_AT="apt.tutorial.three.CREATED_AT";
	public static final String POLL_ACTION="apt.tutorial.three.POLL_ACTION";
	private static final String NOTIFY_KEYWORD="snicklefritz";
	private static final int INITIAL_POLL_PERIOD=1000;
	private static final int POLL_PERIOD=60000;
	private Set<Long> seenStatus=new HashSet<Long>();
	private Map<IPostListener, Account> accounts=
					new ConcurrentHashMap<IPostListener, Account>();
	private final Binder binder=new LocalBinder();
	private AtomicBoolean isBatteryLow=new AtomicBoolean(false);
	private AlarmManager alarm=null;
	private PendingIntent pi=null;

	public PostMonitor() {
		super("PostMonitor");
	}
	
	@Override
	public void onCreate() {
		super.onCreate();
		
		registerReceiver(onBatteryChanged,
											new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		
		alarm=(AlarmManager)getSystemService(Context.ALARM_SERVICE);

		Intent i=new Intent(this, OnAlarmReceiver.class);

		pi=PendingIntent.getBroadcast(this, 0, i, 0);
		setAlarm(INITIAL_POLL_PERIOD);
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return(binder);
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		
		alarm.cancel(pi);
		unregisterReceiver(onBatteryChanged);
	}
	
	@Override
	protected void doWakefulWork(Intent i) {
		if (i.getAction().equals(POLL_ACTION)) {
			for (Account l : accounts.values()) {
				poll(l);
			}
		}
		
		setAlarm(isBatteryLow.get() ? POLL_PERIOD*10 : POLL_PERIOD);
	}
	
	private void setAlarm(long period) {
		alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
							SystemClock.elapsedRealtime()+period,
							pi);
	}
	
	private void poll(Account l) {
		try {
			Twitter client=new Twitter(l.user, l.password);

			client.setAPIRootUrl("https://identi.ca/api");

			List<Twitter.Status> timeline=client.getFriendsTimeline();
			
			for (Twitter.Status s : timeline) {
				if (!seenStatus.contains(s.id)) {
					try {
						Intent broadcast=new Intent(STATUS_UPDATE);
						broadcast.putExtra(FRIEND, s.user.screenName);
						broadcast.putExtra(STATUS, s.text);
						broadcast.putExtra(CREATED_AT,
																s.createdAt.toString());
						sendBroadcast(broadcast);
					}
					catch (Throwable t) {
						Log.e("PostMonitor", "Exception in callback", t);
					}

					seenStatus.add(s.id);
					
					if (s.text.indexOf(NOTIFY_KEYWORD)>-1) {
						showNotification();
					}
				}
			}
		}
		catch (Throwable t) {
			android.util.Log.e("PostMonitor",
												 "Exception in poll()", t);
		}
	}
	
	private void showNotification() {
		final NotificationManager mgr=
			(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		Notification note=new Notification(R.drawable.status,
																				"New matching post!",
																				System.currentTimeMillis());
		Intent i=new Intent(this, Patchy.class);
		
		i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|
							 Intent.FLAG_ACTIVITY_SINGLE_TOP);
		
		PendingIntent pi=PendingIntent.getActivity(this, 0,
																							i,
																							0);
		
		note.setLatestEventInfo(this, "Identi.ca Post!",
														"Found your keyword: "+NOTIFY_KEYWORD,
														pi);
		
		mgr.notify(NOTIFICATION_ID, note);
	}
	
	BroadcastReceiver onBatteryChanged=new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			int pct=100
								*intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 1)
								/intent.getIntExtra(BatteryManager.EXTRA_SCALE, 1);
			
			isBatteryLow.set(pct<=25);
		}
	};
	
	class Account {
		String user=null;
		String password=null;
		IPostListener callback=null;
		
		Account(String user, String password,
						 IPostListener callback) {
			this.user=user;
			this.password=password;
			this.callback=callback;
		}
	}
	
	public class LocalBinder extends Binder implements IPostMonitor {
		public void registerAccount(String user, String password,
																	IPostListener callback) {
			Account l=new Account(user, password, callback);
			
			poll(l);
			accounts.put(callback, l);
		}
		
		public void removeAccount(IPostListener callback) {
			accounts.remove(callback);
		}
	}
}
