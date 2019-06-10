package net.coconauts.notificationListener;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.support.v4.app.NotificationManagerCompat;
import android.text.TextUtils;
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
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static android.app.Notification.EXTRA_CHANNEL_ID;
import static android.os.Process.myPid;
import static android.provider.Settings.EXTRA_APP_PACKAGE;

public class NotificationCommands extends CordovaPlugin {

    private static final String ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners";
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
          this.checkNotifySetting();
          this.ensureCollectorRunning(context);
          this.setListener(callbackContext);
        return true;
      } else {
        callbackContext.error(TAG+". " + action + " is not a supported function.");
        return false;
      }
    }




    private void checkNotifySetting() {
        boolean isOpened = isNotificationListenersEnabled();
        if (isOpened) {
            Log.e(TAG, "通知监听权限已经被打开" +
                    "\n手机型号:" + android.os.Build.MODEL +
                    "\nSDK版本:" + android.os.Build.VERSION.SDK +
                    "\n系统版本:" + android.os.Build.VERSION.RELEASE +
                    "\n软件包名:" + this.cordova.getContext().getPackageName());

        } else {
            Log.e(TAG, "还没有开启通知权限，去开启");
            this.goToNotificationAccessSetting(this.cordova.getActivity());

        }
    }
    private boolean isNotificationListenersEnabled() {
        String pkgName = this.cordova.getContext().getPackageName();
        String flat = Settings.Secure.getString(this.cordova.getContext().getContentResolver(),ENABLED_NOTIFICATION_LISTENERS);
        if (!TextUtils.isEmpty(flat)) {
            String[] names = flat.split(":");
            for (String name : names) {
                ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    return TextUtils.equals(pkgName, cn.getPackageName());
                }
            }
        }
        return false;
    }
    public static boolean goToNotificationAccessSetting(Context context) {
        try {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {//普通情况下找不到的时候需要再特殊处理找一次
            try {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ComponentName cn = new ComponentName("com.android.settings", "com.android.settings.Settings$NotificationAccessSettingsActivity");
                intent.setComponent(cn);
                intent.putExtra(":settings:show_fragment", "NotificationAccessSettings");
                context.startActivity(intent);
                return true;
            } catch (Exception e1) {
                e1.printStackTrace();
            }
            Toast.makeText(context, "对不起，您的手机暂不支持", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
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
