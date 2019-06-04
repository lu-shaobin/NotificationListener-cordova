package net.coconauts.notificationListener;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.Gravity;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.util.Log;
import org.apache.cordova.PluginResult;
import android.service.notification.StatusBarNotification;
import android.os.Bundle;

import java.util.List;

import static android.os.Process.myPid;

public class NotificationCommands extends CordovaPlugin {

    private static final String TAG = "NotificationCommands";

    private static final String LISTEN = "listen";

    // note that webView.isPaused() is not Xwalk compatible, so tracking it poor-man style
    private boolean isPaused;

    private static CallbackContext listener;

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {

      Log.i(TAG, "Received action " + action);
      Context context = this.cordova.getActivity();

      if (LISTEN.equals(action)) {
        ensureCollectorRunning(context);
        setListener(callbackContext);
        return true;
      } else {
        callbackContext.error(TAG+". " + action + " is not a supported function.");
        return false;
      }
    }


    //确认NotificationMonitor是否开启
    private void ensureCollectorRunning(Context context) {
        ComponentName collectorComponent = new ComponentName(context, NotificationService.class);
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        boolean collectorRunning = false;
        List<ActivityManager.RunningServiceInfo> runningServices = manager.getRunningServices(Integer.MAX_VALUE);
        if (runningServices == null ) {
            return;
        }
        for (ActivityManager.RunningServiceInfo service : runningServices) {
            if (service.service.equals(collectorComponent)) {
                if (service.pid == android.os.Process.myPid() ) {
                    collectorRunning = true;
                }
            }
        }
        if (collectorRunning) {
            return;
        }
        toggleNotificationListenerService(context);
    }


    //重新开启NotificationMonitor
    private void toggleNotificationListenerService(Context context) {
        ComponentName thisComponent = new ComponentName(context,  NotificationService.class);
        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(thisComponent, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(thisComponent, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
    }
    

    @Override
    public void onPause(boolean multitasking) {
      this.isPaused = true;
    }

    @Override
    public void onResume(boolean multitasking) {
      this.isPaused = false;
    }

    public void setListener(CallbackContext callbackContext) {
      Log.i("Notification", "Attaching callback context listener " + callbackContext);
      listener = callbackContext;

      PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
      result.setKeepCallback(true);
      callbackContext.sendPluginResult(result);
    }

    public static void notifyListener(StatusBarNotification n){
      if (listener == null) {
        Log.e(TAG, "Must define listener first. Call notificationListener.listen(success,error) first");
        return;
      }
      try  {

        JSONObject json = parse(n);

        PluginResult result = new PluginResult(PluginResult.Status.OK, json);

        Log.i(TAG, "Sending notification to listener " + json.toString());
        result.setKeepCallback(true);

        listener.sendPluginResult(result);
      } catch (Exception e){
        Log.e(TAG, "Unable to send notification "+ e);
        listener.error(TAG+". Unable to send message: "+e.getMessage());
      }
    }


    private static JSONObject parse(StatusBarNotification n)  throws JSONException{

        JSONObject json = new JSONObject();

        Bundle extras = n.getNotification().extras;

        json.put("title", getExtra(extras, "android.title"));
        json.put("package", n.getPackageName());
        json.put("text", getExtra(extras,"android.text"));
        json.put("textLines", getExtraLines(extras, "android.textLines"));

        return json;
    }

    private static String getExtraLines(Bundle extras, String extra){
        try {
            CharSequence[] lines = extras.getCharSequenceArray(extra);
            return lines[lines.length-1].toString();
        } catch( Exception e){
            Log.d(TAG, "Unable to get extra lines " + extra);
            return "";
        }
    }
    private static String getExtra(Bundle extras, String extra){
        try {
            return extras.get(extra).toString();
        } catch( Exception e){
            return "";
        }
    }
}
