package moe.huzerovo.fncalls.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A curated set of system API hook rules shown in the module UI.
 *
 * <p>Rule syntax: {@code fully.qualified.ClassName#method1,method2}.
 * {@code *} and {@code ?} are wildcards. Use {@code <init>} for constructors.</p>
 */
public final class Presets {

    public static final class Preset {
        public final String name;
        public final String description;
        public final List<String> rules;

        Preset(String name, String description, String... rules) {
            this.name = name;
            this.description = description;
            this.rules = Collections.unmodifiableList(Arrays.asList(rules));
        }
    }

    private Presets() {
    }

    public static final List<Preset> ALL = Collections.unmodifiableList(Arrays.asList(
            new Preset("文件与 IO",
                    "文件、流、NIO 与缓冲读写",
                    "java.io.File#*",
                    "java.io.FileInputStream#*",
                    "java.io.FileOutputStream#*",
                    "java.io.RandomAccessFile#*",
                    "java.io.BufferedReader#read,readLine",
                    "java.io.BufferedWriter#write,newLine",
                    "java.nio.file.Files#*"),
            new Preset("网络",
                    "URL、Socket、DNS 与 WebView 请求",
                    "java.net.URL#openConnection,openStream",
                    "java.net.URLConnection#connect,getInputStream,getOutputStream",
                    "java.net.Socket#connect,getInputStream,getOutputStream",
                    "java.net.DatagramSocket#send,receive",
                    "java.net.InetAddress#getByName,getAllByName,getHostName,getHostAddress",
                    "android.webkit.WebView#loadUrl,loadData,loadDataWithBaseURL,postUrl,evaluateJavascript",
                    "com.android.okhttp.internal.huc.HttpURLConnectionImpl#*",
                    "com.android.okhttp.internal.huc.HttpsURLConnectionImpl#*"),
            new Preset("运行时与进程",
                    "命令执行、动态加载、系统属性",
                    "java.lang.Runtime#exec,load,loadLibrary",
                    "java.lang.ProcessBuilder#start,command",
                    "java.lang.System#load,loadLibrary,getenv,getProperty,setProperty,exit",
                    "android.os.Process#killProcess,sendSignal,myPid,myUid",
                    "android.os.SystemProperties#get,getInt,getLong,getBoolean,set"),
            new Preset("反射",
                    "类查找、方法/字段反射调用",
                    "java.lang.Class#forName,getMethod,getDeclaredMethod,getField,getDeclaredField,getConstructor,getDeclaredConstructor,newInstance,getClassLoader",
                    "java.lang.reflect.Method#invoke",
                    "java.lang.reflect.Field#get,set,getInt,setInt,getBoolean,setBoolean"),
            new Preset("组件与生命周期",
                    "Activity / Service / Application / Intent",
                    "android.app.Activity#onCreate,onStart,onResume,onPause,onStop,onDestroy,onRestart,onNewIntent,startActivity,startActivityForResult,setContentView,finish",
                    "android.app.Application#onCreate,onAttach,onTerminate",
                    "android.app.Service#onCreate,onStartCommand,onBind,onUnbind,onDestroy",
                    "android.content.ContextWrapper#startActivity,startService,bindService,sendBroadcast,registerReceiver,unregisterReceiver,getSharedPreferences,openFileOutput,openFileInput",
                    "android.content.Intent#<init>,setAction,setData,setComponent,putExtra,getStringExtra,getIntExtra,getExtras"),
            new Preset("数据与存储",
                    "SQLite、SharedPreferences、ContentResolver",
                    "android.database.sqlite.SQLiteDatabase#openOrCreateDatabase,openDatabase,execSQL,rawQuery,insert,update,delete,query",
                    "android.app.SharedPreferencesImpl#getString,getInt,getLong,getBoolean,getFloat,getStringSet,getAll,contains,edit",
                    "android.content.ContentResolver#query,insert,update,delete,call,openInputStream,openOutputStream"),
            new Preset("设备信息与隐私",
                    "电话、设备标识、设置、账号与包信息",
                    "android.telephony.TelephonyManager#getDeviceId,getImei,getMeid,getSubscriberId,getSimSerialNumber,getLine1Number,getNetworkOperator,getNetworkOperatorName,getSimOperator,getSimOperatorName,getNetworkType,getPhoneType,getAllCellInfo,getCellLocation",
                    "android.provider.Settings$Secure#getString,getInt,getLong,getFloat",
                    "android.provider.Settings$System#getString,getInt,getLong,getFloat",
                    "android.provider.Settings$Global#getString,getInt,getLong,getFloat",
                    "android.accounts.AccountManager#getAccounts,getAccountsByType,getAuthToken",
                    "android.content.pm.PackageManager#getInstalledPackages,getInstalledApplications,getPackageInfo,getApplicationInfo",
                    "android.app.ApplicationPackageManager#getInstalledPackages,getInstalledApplications,getPackageInfo,getApplicationInfo"),
            new Preset("位置与传感器",
                    "定位、传感器、相机与麦克风",
                    "android.location.LocationManager#getLastKnownLocation,getCurrentLocation,requestLocationUpdates,removeUpdates,isProviderEnabled,getProviders,getAllProviders",
                    "android.hardware.SensorManager#registerListener,unregisterListener,getSensorList,getDefaultSensor",
                    "android.hardware.Camera#open",
                    "android.media.MediaRecorder#start,setAudioSource,setOutputFile",
                    "android.media.AudioRecord#startRecording,read"),
            new Preset("加密",
                    "Cipher、摘要、密钥库与签名",
                    "javax.crypto.Cipher#getInstance,init,doFinal,update,wrap,unwrap",
                    "java.security.MessageDigest#getInstance,digest,update",
                    "java.security.KeyStore#getInstance,load,getKey,getCertificate,setKeyEntry",
                    "java.security.Signature#getInstance,initSign,initVerify,sign,verify",
                    "javax.crypto.Mac#getInstance,init,doFinal,update"),
            new Preset("WebView 与 JS 桥",
                    "网页加载与 JavaScript 接口",
                    "android.webkit.WebView#loadUrl,loadData,loadDataWithBaseURL,postUrl,evaluateJavascript,addJavascriptInterface,setWebViewClient,setWebChromeClient",
                    "android.webkit.WebViewClient#shouldOverrideUrlLoading,onPageStarted,onPageFinished,shouldInterceptRequest"),
            new Preset("日志与系统属性",
                    "Log 与 SystemProperties（日志量较大）",
                    "android.util.Log#v,d,i,w,e,wtf,println",
                    "android.os.SystemProperties#get,getInt,getLong,getBoolean,set"),
            new Preset("权限与敏感操作",
                    "运行时权限、剪贴板、Wi-Fi、蓝牙、NFC",
                    "android.app.Activity#requestPermissions,checkSelfPermission",
                    "android.content.ContextWrapper#checkSelfPermission,checkCallingOrSelfPermission",
                    "android.content.ClipboardManager#getPrimaryClip,setPrimaryClip,hasPrimaryClip",
                    "android.net.wifi.WifiManager#getConnectionInfo,getScanResults,isWifiEnabled,setWifiEnabled",
                    "android.bluetooth.BluetoothAdapter#getAddress,getName,getBondedDevices,startDiscovery",
                    "android.nfc.NfcAdapter#enableReaderMode,disableReaderMode")
    ));

    public static final String DEFAULT_RULES_TEXT = String.join("\n", Arrays.asList(
            "java.net.URL#openConnection,openStream",
            "java.net.Socket#connect,getInputStream,getOutputStream",
            "java.lang.Runtime#exec,loadLibrary",
            "java.lang.ProcessBuilder#start",
            "android.app.Activity#onCreate,onStart,onResume,onPause,onStop,onDestroy,startActivity,startActivityForResult,setContentView",
            "android.app.Application#onCreate,onAttach",
            "android.content.ContextWrapper#startActivity,startService,bindService,sendBroadcast,registerReceiver,getSharedPreferences,openFileOutput,openFileInput",
            "android.content.Intent#<init>,setAction,putExtra,getStringExtra",
            "android.telephony.TelephonyManager#getDeviceId,getImei,getSubscriberId,getLine1Number,getNetworkOperator,getSimOperator,getNetworkType",
            "android.location.LocationManager#getLastKnownLocation,requestLocationUpdates,getProviders"
    ));

    public static List<String> rulesFor(int... indexes) {
        List<String> out = new ArrayList<>();
        for (int index : indexes) {
            if (index >= 0 && index < ALL.size()) {
                out.addAll(ALL.get(index).rules);
            }
        }
        return out;
    }
}
