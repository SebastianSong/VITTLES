package kaist.game.battlecar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import org.freedesktop.gstreamer.GStreamer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import kaist.game.battlecar.view.GStreamerSurfaceView;
import kaist.game.battlecar.view.JoystickView;

public class PlayActivity extends Activity implements SurfaceHolder.Callback {
    private native void nativeInit();     // Initialize native code, build pipeline, etc
    private native void nativeFinalize(); // Destroy pipeline and shutdown native code
    private native void nativeSetUri(String uri); // Set the URI of the media to play
    private native void nativePlay();     // Set pipeline to PLAYING
    private native void nativeSetPosition(int milliseconds); // Seek to the indicated position, in milliseconds
    private native void nativePause();    // Set pipeline to PAUSED
    private static native boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks
    private native void nativeSurfaceInit(Object surface); // A new surface is available
    private native void nativeSurfaceFinalize(); // Surface about to be destroyed
    private long native_custom_data;      // Native code will use this to keep private data

    private boolean is_playing_desired;   // Whether the user asked to go to PLAYING
    private int position;                 // Current position, reported by native code
    private int duration;                 // Current clip duration, reported by native code
    private boolean is_local_media;       // Whether this clip is stored locally or is being streamed
    private int desired_position;         // Position where the users wants to seek to
    private String mediaUri;              // URI of the clip being played

    private final String defaultMediaUri = "http://docs.gstreamer.com/media/sintel_trailer-368p.ogv";

    static private final int PICK_FILE_CODE = 1;
    private String last_folder;

    private PowerManager.WakeLock wake_lock;

    private int currentApiVersion;

    private TextView angleTextView;
    private TextView powerTextView;
    private TextView directionTextView;
    private JoystickView joystick;
    private SharedPreferences setting;

    // Called when the activity is first created.
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        // Initialize GStreamer and warn if it fails
        try {
            GStreamer.init(this);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);


        currentApiVersion = android.os.Build.VERSION.SDK_INT;

        // This work only for android 4.4+
        if(currentApiVersion >= Build.VERSION_CODES.KITKAT)
        {
            final int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);

            // Code below is to handle presses of Volume up or Volume down.
            // Without this, after pressing volume buttons, the navigation bar will
            // show up and won't hide
            final View decorView = getWindow().getDecorView();
            decorView.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
                @Override
                public void onSystemUiVisibilityChange(int visibility) {
                    if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                        decorView.setSystemUiVisibility(flags);
                    }
                }
            });
        }

        setContentView(R.layout.activity_play);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake_lock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "GStreamer battle car");
        wake_lock.setReferenceCounted(false);

        setJoyStickView();

        WifiManager wm = (WifiManager) getSystemService(WIFI_SERVICE);
        int ip = wm.getConnectionInfo().getIpAddress();
        final String wifiIpAddress = String.format("%d.%d.%d.%d",
                (ip & 0xff),
                (ip >> 8 & 0xff),
                (ip >> 16 & 0xff),
                (ip >> 24 & 0xff));
        ImageButton play = (ImageButton) this.findViewById(R.id.button_play);
        play.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                String vittlesUrl = setting.getString("vittles_url", "");
                new BackgroundTask(vittlesUrl + "/camonoff/" + wifiIpAddress).execute();
            }
        });

        ImageButton pause = (ImageButton) this.findViewById(R.id.button_stop);
        pause.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                /*is_playing_desired = false;
                wake_lock.release();
                nativePause();*/
            }
        });

        SurfaceView sv = (SurfaceView) this.findViewById(R.id.surface_video);
        SurfaceHolder sh = sv.getHolder();
        sh.addCallback(this);

        // Retrieve our previous state, or initialize it to default values
        if (savedInstanceState != null) {
            is_playing_desired = savedInstanceState.getBoolean("playing");
            position = savedInstanceState.getInt("position");
            duration = savedInstanceState.getInt("duration");
            mediaUri = savedInstanceState.getString("mediaUri");
            last_folder = savedInstanceState.getString("last_folder");
            Log.i ("GStreamer", "Activity created with saved state:");
        } else {
            is_playing_desired = false;
            position = duration = 0;
            last_folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).getAbsolutePath();
            Intent intent = getIntent();
            android.net.Uri uri = intent.getData();
            if (uri == null)
                mediaUri = defaultMediaUri;
            else {
                Log.i ("GStreamer", "Received URI: " + uri);
                if (uri.getScheme().equals("content")) {
                    android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                    cursor.moveToFirst();
                    mediaUri = "file://" + cursor.getString(cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA));
                    cursor.close();
                } else
                    mediaUri = uri.toString();
            }
            Log.i ("GStreamer", "Activity created with no saved state:");
        }
        is_local_media = false;
        Log.i("GStreamer", "  playing:" + is_playing_desired + " position:" + position +
                " duration: " + duration + " uri: " + mediaUri);

        // Start with disabled buttons, until native code is initialized
        this.findViewById(R.id.button_play).setEnabled(false);
        this.findViewById(R.id.button_stop).setEnabled(false);

        nativeInit();

        setting =  PreferenceManager.getDefaultSharedPreferences(this);
    }

    class BackgroundTask extends AsyncTask<Integer, Integer, Integer> {
        private String mURL;

        public BackgroundTask(String url){
            mURL = url;
        }
        protected void onPreExecute() {
        }

        @Override
        protected Integer doInBackground(Integer... arg0) {
            // TODO Auto-generated method stub
            request(mURL);
            return null;
        }

        protected void onPostExecute(Integer a) {
            //tv.setText(result);
        }

    }

    private String request(String urlStr) {
        StringBuilder output = new StringBuilder();
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            if (conn != null) {
                conn.setConnectTimeout(10000);
                //conn.setRequestMethod("GET");
                //conn.setDoInput(true);
                //conn.setDoOutput(true);

                int resCode = conn.getResponseCode();
                if (resCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream())) ;
                    String line = null;
                    while(true) {
                        line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        output.append(line + "\n");
                    }

                    reader.close();
                    conn.disconnect();
                }
            }
        } catch(Exception ex) {
            Log.e("SampleHTTP", "Exception in processing response.", ex);
            ex.printStackTrace();
        }

        return output.toString();
    }

    private void setJoyStickView() {
        angleTextView = (TextView) findViewById(R.id.angleTextView);
        powerTextView = (TextView) findViewById(R.id.powerTextView);
        directionTextView = (TextView) findViewById(R.id.directionTextView);
        //Referencing also other views
        joystick = (JoystickView) findViewById(R.id.joystickView);

        //Event listener that always returns the variation of the angle in degrees, motion power in percentage and direction of movement
        joystick.setOnJoystickMoveListener(new JoystickView.OnJoystickMoveListener() {

            @Override
            public void onValueChanged(int angle, int power, int direction) {
                final String delimiter = "$";
                int movement = 0;
                int steering = 0;
                int weapon = 0;

                angleTextView.setText(" " + String.valueOf(angle) + "°");
                powerTextView.setText(" " + String.valueOf(power) + "%");

                switch (direction) {
                    case JoystickView.FRONT:
                        movement = 1;
                        directionTextView.setText("front");
                        break;
                    case JoystickView.FRONT_RIGHT:
                        movement = 1;
                        directionTextView.setText("front_right");
                        break;
                    case JoystickView.RIGHT:
                        movement = 0;
                        directionTextView.setText("right");
                        break;
                    case JoystickView.RIGHT_BOTTOM:
                        movement = 2;
                        directionTextView.setText("right_bottom");
                        break;
                    case JoystickView.BOTTOM:
                        movement = 2;
                        directionTextView.setText("bottom");
                        break;
                    case JoystickView.BOTTOM_LEFT:
                        movement = 2;
                        directionTextView.setText("bottom_left");
                        break;
                    case JoystickView.LEFT:
                        movement = 0;
                        directionTextView.setText("left");
                        break;
                    case JoystickView.LEFT_FRONT:
                        movement = 1;
                        directionTextView.setText("left_front");
                        break;
                    default:
                        movement = 0;
                        directionTextView.setText("center");
                }
                if (angle < -23 && angle > -157) {
                    steering = 1;
                } else if (angle > 23 && angle < 157) {
                    steering = 2;
                } else{
                    steering =0;
                }
                if (angle > -113 && angle < -67)
                {
                    steering = 0;
                }  else if (angle > 67  && angle < 113){
                    steering = 0;
                }
                /*
                neutral    0
                movement forward|reverse (1|2)
                steering left|right (1|2)
                "movement,steering,angle(-180~180),power(0~100%),weapon"
                */

                String vittlesUrl = setting.getString("vittles_url", "");
                directionTextView.setText("VITTLES URL: " + vittlesUrl);

                StringBuilder commandCode = new StringBuilder();
                commandCode.append(movement).append(delimiter).append(steering).append(delimiter).append(angle).append(delimiter).append(power).append(delimiter).append(weapon);
                new BackgroundTask(vittlesUrl + "/inputBattleCar/" + commandCode.toString()).execute();
            }
        }, JoystickView.DEFAULT_LOOP_INTERVAL);
    }

    protected void onSaveInstanceState(Bundle outState) {
        Log.d("GStreamer", "Saving state, playing:" + is_playing_desired + " position:" + position +
                " duration: " + duration + " uri: " + mediaUri);
        outState.putBoolean("playing", is_playing_desired);
        outState.putInt("position", position);
        outState.putInt("duration", duration);
        outState.putString("mediaUri", mediaUri);
        outState.putString("last_folder", last_folder);
    }

    protected void onDestroy() {
        nativeFinalize();
        if (wake_lock.isHeld())
            wake_lock.release();
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // Called from native code. This sets the content of the TextView from the UI thread.
    private void setMessage(final String message) {
        final TextView tv = (TextView) this.findViewById(R.id.textview_message);
        runOnUiThread (new Runnable() {
            public void run() {
                tv.setText(message);
            }
        });
    }

    // Set the URI to play, and record whether it is a local or remote file
    private void setMediaUri() {
        //nativeSetUri (mediaUri);
        //is_local_media = mediaUri.startsWith("file://");
    }

    // Called from native code. Native code calls this once it has created its pipeline and
    // the main loop is running, so it is ready to accept commands.
    private void onGStreamerInitialized () {
        Log.i ("GStreamer", "GStreamer initialized:");
        Log.i ("GStreamer", "  playing:" + is_playing_desired + " position:" + position + " uri: " + mediaUri);

        // Restore previous playing state
        setMediaUri ();
        nativeSetPosition (position);
        //if (is_playing_desired) {
            nativePlay();
            wake_lock.acquire();
        //}
        /* else {
            nativePause();
            wake_lock.release();
        }*/

        // Re-enable buttons, now that GStreamer is initialized
        final Activity activity = this;
        runOnUiThread(new Runnable() {
            public void run() {
                activity.findViewById(R.id.button_play).setEnabled(true);
                activity.findViewById(R.id.button_stop).setEnabled(true);
            }
        });
    }


    // Called from native code
    private void setCurrentPosition(final int position, final int duration) {
        /*final SeekBar sb = (SeekBar) this.findViewById(R.id.seek_bar);

        // Ignore position messages from the pipeline if the seek bar is being dragged
        if (sb.isPressed()) return;

        runOnUiThread (new Runnable() {
            public void run() {
                sb.setMax(duration);
                sb.setProgress(position);
                updateTimeWidget();
                sb.setEnabled(duration != 0);
            }
        });*/
        this.position = position;
        this.duration = duration;
    }

    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("CarViewStreamer");
        nativeClassInit();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
        Log.d("GStreamer", "Surface changed to format " + format + " width "
                + width + " height " + height);
        nativeSurfaceInit(holder.getSurface());
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface created: " + holder.getSurface());
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface destroyed");
        nativeSurfaceFinalize ();
    }

    // Called from native code when the size of the media changes or is first detected.
    // Inform the video surface about the new size and recalculate the layout.
    private void onMediaSizeChanged (int width, int height) {
        Log.i ("GStreamer", "Media size changed to " + width + "x" + height);
        final GStreamerSurfaceView gsv = (GStreamerSurfaceView) this.findViewById(R.id.surface_video);
        gsv.media_width = width;
        gsv.media_height = height;
        runOnUiThread(new Runnable() {
            public void run() {
                gsv.requestLayout();
            }
        });
    }
}