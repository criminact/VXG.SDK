package veg.mediacapture.sdk.servicetest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import veg.mediacapture.sdk.MediaCapture;
import veg.mediacapture.sdk.MediaCapture.CaptureNotifyCodes;
import veg.mediacapture.sdk.MediaCapture.PlayerRecordFlags;
import veg.mediacapture.sdk.MediaCaptureCallback;
import veg.mediacapture.sdk.MediaCaptureConfig;
import veg.mediacapture.sdk.MediaCaptureConfig.CaptureVideoResolution;


public class MediaCaptureService extends Service {
	protected static final int NOTIFICATION_ID_BASE = 31337;
	public static final int NOTIFICATION_SERVICE_RUNNING_ID = NOTIFICATION_ID_BASE + 1;
	final String TAG = "MediaCaptureService";
	private Runner runner = null;
	public static NotificationManager nm;
	private final IBinder mBinder = new MyBinder();
	public MediaCapture capturer = null;
	public MediaCaptureConfig config = null;
	public String rtmp_url = "rtmp://192.168.0.102:1935/live/sys";

	public class MyBinder extends Binder {
		public MediaCaptureService getService() {
			return MediaCaptureService.this;
		}
	}

	public MediaCaptureService() {
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	public String getRecordPath() {
		File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
				Environment.DIRECTORY_DCIM), "RecordsMediaStreamer");

		if (!mediaStorageDir.exists()) {
			if (!(mediaStorageDir.mkdirs() || mediaStorageDir.isDirectory())) {
				Log.e(TAG, "<=getRecordPath() failed to create directory path=" + mediaStorageDir.getPath());
				return "";
			}
		}
		return mediaStorageDir.getPath();
	}

	int get_record_flags() {
		int flags = PlayerRecordFlags.forType(PlayerRecordFlags.PP_RECORD_AUTO_START) | PlayerRecordFlags.forType(PlayerRecordFlags.PP_RECORD_SPLIT_BY_TIME);    // auto start and split by time
		//int flags = PlayerRecordFlags.forType(PlayerRecordFlags.PP_RECORD_AUTO_START) | PlayerRecordFlags.forType(PlayerRecordFlags.PP_RECORD_SPLIT_BY_SIZE);	// auto start and split by size
		return flags;
	}

	public int onStartCommand(Intent intent, int flags, int startId) {
		if (runner == null) {
			capturer = new MediaCapture(MediaCaptureService.this, null);
			config = capturer.getConfig();

			config.setStreaming(false);
			config.setRecording(false);
			config.setTranscoding(true);

			//set portrait
			config.setVideoOrientation(MediaCaptureConfig.MC_ORIENTATION_PORTRAIT);
			//config.setVideoOrientation(MediaCaptureConfig.MC_ORIENTATION_LANDSCAPE);

			//set camere facing
			config.setCameraFacing(MediaCaptureConfig.CAMERA_FACING_BACK);
			//config.setCameraFacing(MediaCaptureConfig.CAMERA_FACING_FRONT);

			//video only
			//config.setCaptureMode(MediaCaptureConfig.CaptureModes.PP_MODE_VIDEO.val());


			//main channel
			config.setCaptureSource(MediaCaptureConfig.CaptureSources.PP_MODE_CAMERA.val());
			config.setStreamType(MediaCaptureConfig.StreamerTypes.STREAM_TYPE_RTMP_PUBLISH.val());
			config.setUrl(rtmp_url);
			config.setVideoResolution(MediaCaptureConfig.CaptureVideoResolution.VR_640x480);
			config.setVideoBitrate(800);
			config.setVideoFramerate(30);
			config.setVideoKeyFrameInterval(1);
			config.setVideoBitrateMode(MediaCaptureConfig.BITRATE_MODE_VBR);

			//main channel recording
			int record_flags = get_record_flags();
			int rec_split_time = ((record_flags & PlayerRecordFlags.forType(PlayerRecordFlags.PP_RECORD_SPLIT_BY_TIME)) != 0) ? 60 : 0; //60 sec
			config.setRecordPrefix("main");
			config.setRecordPath(getRecordPath());
			config.setRecordFlags(record_flags);
			config.setRecordSplitTime(rec_split_time);//in sec
			config.setRecordSplitSize(100); //in MB

			//secondary channel
			boolean is_secvideo = true;
			config.setUseSecControl(true);
			config.setSecVideoResolution(CaptureVideoResolution.VR_320x240);
			config.setSecVideoFramerate(30);
			config.setSecVideoKeyFrameInterval(1);
			config.setSecVideoBitrate(400);
			config.setVideoSecBitrateMode(MediaCaptureConfig.BITRATE_MODE_VBR);

			//secondary streaming
			config.setUrlSec(rtmp_url + "2");

			//secondary recording
			config.setUseSec(true);
			config.setRecordPrefixSec("secondary");
			config.setRecordPathSec(getRecordPath());
			config.setRecordFlagsSec(record_flags);
			config.setRecordSplitTimeSec(rec_split_time);//in sec
			config.setRecordSplitSizeSec(10); //in MB

			//audio
			config.setAudioFormat(MediaCaptureConfig.TYPE_AUDIO_AAC);
			config.setAudioBitrate(32);
			config.setAudioSamplingRate(44100);
			config.setAudioChannels(2);

			//timeshift
			config.setRecordTimeshift(10);
			config.setRecordTimeshiftSec(5);


			MediaCaptureCallback captureCallback = new MediaCaptureCallback() {
				@Override
				public int OnCaptureStatus(int arg) {

					Log.d(TAG, "ON STATUS " + arg);

					String strText = null;

					CaptureNotifyCodes status = CaptureNotifyCodes.forValue(arg);

					switch (status) {
						case CAP_OPENED:
							strText = "Opened";
							break;
						case CAP_SURFACE_CREATED:
							strText = "Capture surface created";
							break;
						case CAP_SURFACE_DESTROYED:
							strText = "Capture surface destroyed";
							break;
						case CAP_STARTED:
							strText = "Started";
							break;
						case CAP_STOPPED:
							strText = "Stopped";
							break;
						case CAP_CLOSED:
							strText = "Closed";
							break;
						case CAP_ERROR:
							strText = "Error";
							break;
						case CAP_TIME:
							int rtmp_status = capturer.getStreamStatus();
							int dur = (int) (long) capturer.getDuration() / 1000;
							int v_cnt = capturer.getVideoPackets();
							int a_cnt = capturer.getAudioPackets();
							long v_pts = capturer.getLastVideoPTS();
							long a_pts = capturer.getLastAudioPTS();
							int nreconnects = capturer.getStatReconnectCount();

							String sss = "";
							String sss2 = "";
							int min = dur / 60;
							int sec = dur - (min * 60);
							sss = String.format("%02d:%02d", min, sec);

							if (rtmp_status == (-999)) {
								sss = "Streaming stopped. DEMO VERSION limitation";
								capturer.Stop();
							} else if (rtmp_status != (-1)) {
								if (capturer.USE_RTSP_SERVER) {
									sss += ". RTSP ON (" + capturer.getRTSPAddr() + ")";
									sss2 += "v:" + v_cnt + " a:" + a_cnt + " rcc:" + nreconnects;
								} else {
									sss += ". RTMP " + ((rtmp_status == 0) ? "ON ( " + rtmp_url + " )" : "Err:" + rtmp_status);
									//sss += ". RTMP "+ ((rtmp_status == 0)?"ON ":"Err:"+rtmp_status);
									if (rtmp_status == (-5)) {
										sss += " Server not connected ( " + rtmp_url + " )";
									} else if (rtmp_status == (-12)) {
										sss += " Out of memory";
									}
									sss2 += "v:" + v_cnt + " a:" + a_cnt + " rcc:" + nreconnects;
									sss2 += "\nv_pts: " + v_pts + " a_pts: " + a_pts + " delta: " + (v_pts - a_pts);
								}

							} else {
								// rtmp_status == (-1)
								sss += ". Connecting ...";
							}

							String sss3 = "";
							int rec_status = capturer.getRECStatus();
							if (rec_status != -1) {
								if (rec_status == (-999)) {
									sss = "Streaming stopped. DEMO VERSION limitation";
									capturer.Stop();
								} else if (rec_status != 0 && rec_status != (-999)) {
									sss3 += "REC Err:" + rec_status;
								} else
									sss3 += "REC ON. " + capturer.getPropString(MediaCapture.PlayerRecordStat.forType(MediaCapture.PlayerRecordStat.PP_RECORD_STAT_FILE_NAME));
							}

							String sss4 = "";
							if (config.getStreamType() == MediaCaptureConfig.StreamerTypes.STREAM_TYPE_RTMP_PUBLISH.val() &&
									(config.isUseSecStreaming() || config.isUseSecRecord())) {
								sss4 = "Secondary channel: ";
								int rtmp_status_sec = capturer.getStreamStatusSec();
								if (rtmp_status_sec != (-1)) {
									sss4 += " RTMP " + ((rtmp_status_sec == 0) ? "ON ( " + rtmp_url + " )" : "Err:" + rtmp_status_sec);
								} else {
									sss4 += ". Connecting ...";
								}

								int rec_status_sec = capturer.getRECStatusSec();
								if (rec_status_sec != -1 && rec_status != (-999)) {
									sss4 += " REC ON. " + capturer.getPropString(MediaCapture.PlayerRecordStat.forType(MediaCapture.PlayerRecordStat.PP_RECORD_STAT_FILE_NAME_SEC));
								}
							}

							strText = sss + " " + sss2 + " " + sss3 + " " + sss4;
					}
					Intent intent = new Intent(MainActivity.BROADCAST_ACTION);
					intent.putExtra(MainActivity.PARAM_STATUS,
							MainActivity.STATUS_UPDATE)
							.putExtra(MainActivity.PARAM_STATUS_TEXT, strText);
					MediaCaptureService.super.sendBroadcast(intent);

					return 0;
				}

				@Override
				public int OnCaptureReceiveData(ByteBuffer buffer, int type, int size, long pts) {
					Log.d(TAG, "ON CAPTURE RECI");

					if (buffer == null) {
						Log.e(TAG, "=OnCaptureReceiveData, buffer is null");
						return 0;
					}

					if (type != 0) { // not video frame
						Log.e(TAG, "=OnCaptureReceiveData, it's not video frame");
						return 0;
					}

					File filePreview = new File(getRecordPath(), "preview.jpg");
					Log.e(TAG, "=OnCaptureReceiveData, filePreview " + filePreview.getAbsolutePath());
					if (filePreview.exists()) {
						//Log.e(TAG, "=OnCaptureReceiveData, preview already exists");
						//StopTranscoding();
						//return 0;
						filePreview.delete();
					}

					int width = capturer.getConfig().getTransWidth(); //320;
					int height = capturer.getConfig().getTransHeight(); //240;
					Log.v(TAG, "=OnCaptureReceiveData, buffer=" + buffer + " type=" + type + " size=" + size + " pts=" + pts);
					Log.i(TAG, "=OnCaptureReceiveData, Send image buffer.capacity() " + buffer.capacity());
					Log.i(TAG, "=OnCaptureReceiveData, Send image buffer.capacity() expected " + (width * height * 4));
					Log.i(TAG, "=OnCaptureReceiveData, Image width " + width);
					Log.i(TAG, "=OnCaptureReceiveData, Image height " + height);

					BufferedOutputStream bos = null;
					try {
						bos = new BufferedOutputStream(new FileOutputStream(filePreview));
						Bitmap bmp0 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
						bmp0.copyPixelsFromBuffer(buffer);

						Matrix matrix = new Matrix();
						matrix.preScale(-1, 1);
						matrix.preRotate(180);
						//matrix.postScale(width, height);
						//matrix.postRotate(180);
						Bitmap bmp = Bitmap.createBitmap(bmp0, 0, 0, width, height, matrix, true);

						bmp.compress(Bitmap.CompressFormat.JPEG, 100, bos);
						bmp0.recycle();
						bmp.recycle();
						if (bos != null) bos.close();
						Log.d(TAG, "IMAGE SAVED");
					} catch (IOException e) {
						Log.e(TAG, "=OnCaptureReceiveData ", e);
					}

					return 0;
				}
			};

			/* Screen Capture Android permission data obtained in MainActivity.onActivityResult() */
			int resultCode = intent.getIntExtra("resultCode", 0);
			capturer.SetPermissionRequestResults(resultCode, intent);

			capturer.Open(config, captureCallback);

			runner = new Runner(startId);
			new Thread(runner).start();
		}

		return START_STICKY;
	}

	class Runner implements Runnable {
		int startId;

		public Runner(int startId) {
			this.startId = startId;
		}

		@Override
		public void run() {
			Intent intent = new Intent(MainActivity.BROADCAST_ACTION);
			MediaCaptureService.super.sendBroadcast(intent.putExtra(MainActivity.PARAM_STATUS,
					MainActivity.STATUS_SERVICE_STARTED));

			Intent resultIntent = new Intent(MediaCaptureService.this, MainActivity.class)
					.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			PendingIntent pendingIntent = PendingIntent.getActivity(MediaCaptureService.this, 0,
					resultIntent, PendingIntent.FLAG_UPDATE_CURRENT);
			Notification notification;
			Notification.Builder builder = new Notification.Builder(MediaCaptureService.this)
					.setContentTitle(getString(R.string.app_name))
					.setContentText(getString(R.string.notify_text) + "\n" + rtmp_url)
					.setSmallIcon(R.drawable.ic_fiber_manual_record_red)
					.setOngoing(true)
					.setContentIntent(pendingIntent)
					.setWhen(0);

			notification = builder.build();
			nm.notify(MediaCaptureService.NOTIFICATION_SERVICE_RUNNING_ID, notification);
		}

		void stop() {
			Intent intent = new Intent(MainActivity.BROADCAST_ACTION);
			intent.putExtra(MainActivity.PARAM_STATUS, MainActivity.STATUS_SERVICE_STOPPED);
			MediaCaptureService.super.sendBroadcast(intent);
			nm.cancel(MediaCaptureService.NOTIFICATION_SERVICE_RUNNING_ID);
		}
	}

	private void startMyOwnForeground(){
		String NOTIFICATION_CHANNEL_ID = "com.example.simpleapp";
		String channelName = "My Background Service";
		NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
		chan.setLightColor(Color.BLUE);
		chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
		NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		assert manager != null;
		manager.createNotificationChannel(chan);

		NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
		Notification notification = notificationBuilder.setOngoing(true)
				.setSmallIcon(R.drawable.ic_fiber_manual_record_red)
				.setContentTitle("App is running in background")
				.setPriority(NotificationManager.IMPORTANCE_MIN)
				.setCategory(Notification.CATEGORY_SERVICE)
				.build();
		startForeground(2, notification);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			startMyOwnForeground();
		else
			startForeground(1, new Notification());

		nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
	}

	public void onDestroy() {
		runner.stop();
		capturer.Stop();
		capturer.Close();
		super.onDestroy();
	}
}
