package com.interview.sdk.plugin.permission

/**
 * 权限审计用到的静态知识库。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ⚠️ 为什么需要「API → 权限」反查表，而不能只扫字符串
 * ══════════════════════════════════════════════════════════════════════
 *
 * 最常见的错误做法是：在 SDK 字节码里 grep `"android.permission.XXX"`。
 * 这**只覆盖了隐式声明**，会大量漏报。原因：
 *
 *   调用 `TelephonyManager.getDeviceId()` **不需要写任何权限字符串**，
 *   权限是系统的运行时检查。SDK 里根本不会出现 "READ_PHONE_STATE" 字样。
 *
 * 所以一个 SDK 可以在「零字符串命中」的情况下实打实依赖 READ_PHONE_STATE。
 * 这类权限如果被判定成「未使用」而移除，SDK 会**静默拿到 null 或抛
 * SecurityException** —— 属于最危险的一类误判。
 *
 * 本表提供这类 API 的反查。它是**启发式且不完备的**：
 *   · 只覆盖常见平台 API，不覆盖厂商 ROM 私有 API
 *   · 标注的权限是该 API 的**典型**要求，具体取决于 OEM 实现与 targetSdk
 * 因此表命中时的结论是「需要该权限」，表未命中**不代表**不需要。
 */
internal object ApiPermissionMap {

    /**
     * key = "JVM 内部名#方法名"（owner 用斜杠），value = 权限短名列表。
     *
     * ⚠️ 用「短名」（如 READ_PHONE_STATE）而非全名，便于 ReportWriter 统一拼前缀，
     *    也便于这张表被复用为「权限 → API」的反向索引。
     */
    private val TABLE: Map<String, List<String>> = buildMap {
        // ─── 电话状态（最常被 SDK 静默依赖的一类）───
        listOf(
            "getDeviceId", "getImei", "getMeid", "getSubscriberId",
            "getSimSerialNumber", "getLine1Number", "getVoiceMailNumber",
            "listen", "getNetworkOperatorName", "getSimOperatorName",
            "getCellLocation", "getAllCellInfo", "requestNetworkScan",
        ).forEach { put("android/telephony/TelephonyManager#$it", listOf("READ_PHONE_STATE")) }
        put("android/telephony/TelephonyManager#getNeighboringCellInfo", listOf("ACCESS_COARSE_LOCATION"))
        put("android/telephony/TelephonyManager#getDeviceSoftwareVersion", listOf("READ_PHONE_STATE"))
        put("android/telephony/PhoneStateListener#onCellLocationChanged", listOf("ACCESS_FINE_LOCATION"))
        put("android/telephony/SubscriptionManager#getActiveSubscriptionInfoList", listOf("READ_PHONE_STATE"))

        // ─── 短信 ───
        listOf("sendTextMessage", "sendMultipartTextMessage", "sendDataMessage")
            .forEach { put("android/telephony/SmsManager#$it", listOf("SEND_SMS")) }
        put("android/provider/Telephony\$Sms#query", listOf("READ_SMS"))

        // ─── 定位 ───
        listOf("requestLocationUpdates", "requestSingleUpdate", "getLastKnownLocation", "addProximityAlert", "addGeofence")
            .forEach { put("android/location/LocationManager#$it", listOf("ACCESS_FINE_LOCATION")) }
        put("androidx/core/location/LocationManagerCompat#requestLocationUpdates", listOf("ACCESS_FINE_LOCATION"))
        put("androidx/core/location/LocationManagerCompat#getLastKnownLocation", listOf("ACCESS_FINE_LOCATION"))

        // ─── 相机 ───
        put("android/hardware/Camera#open", listOf("CAMERA"))
        put("android/hardware/camera2/CameraManager#openCamera", listOf("CAMERA"))

        // ─── 麦克风 / 音频 ───
        put("android/media/AudioRecord#<init>", listOf("RECORD_AUDIO"))
        put("android/media/MediaRecorder#setAudioSource", listOf("RECORD_AUDIO"))
        put("android/media/MediaRecorder#start", listOf("RECORD_AUDIO"))
        put("android/media/AudioRecord\$Builder#build", listOf("RECORD_AUDIO"))

        // ─── 通讯录 ───
        put("android/provider/ContactsContract\$Contacts#query", listOf("READ_CONTACTS"))
        put("android/provider/ContactsContract\$CommonDataKinds\$Phone#query", listOf("READ_CONTACTS"))
        put("android/accounts/AccountManager#getAccounts", listOf("GET_ACCOUNTS"))
        put("android/accounts/AccountManager#getAccountsByType", listOf("GET_ACCOUNTS"))

        // ─── Wi-Fi / 蓝牙 / 附近设备 ───
        put("android/net/wifi/WifiManager#getScanResults", listOf("ACCESS_FINE_LOCATION"))
        put("android/net/wifi/WifiManager#startScan", listOf("ACCESS_FINE_LOCATION"))
        put("android/net/wifi/WifiManager#getConnectionInfo", listOf("ACCESS_WIFI_STATE"))
        put("android/bluetooth/BluetoothAdapter#startDiscovery", listOf("BLUETOOTH_SCAN", "BLUETOOTH_ADMIN"))
        put("android/bluetooth/BluetoothAdapter#startLeScan", listOf("BLUETOOTH_ADMIN"))
        put("android/bluetooth/le/BluetoothLeScanner#startScan", listOf("BLUETOOTH_SCAN"))

        // ─── 存储 / 媒体 ───
        put("android/provider/MediaStore\$Images\$Media#insert", listOf("WRITE_EXTERNAL_STORAGE"))
        put("android/os/Environment#getExternalStorageDirectory", listOf("READ_EXTERNAL_STORAGE"))

        // ─── 其它常见 ───
        put("android/os/Vibrator#vibrate", listOf("VIBRATE"))
        put("android/hardware/SensorManager#registerListener", listOf("BODY_SENSORS"))
        put("android/provider/Settings\$System#canWrite", listOf("WRITE_SETTINGS"))
        put("android/media/MediaMetadataRetriever#setDataSource", listOf("READ_EXTERNAL_STORAGE"))

        // ─── 网络与连接状态（极其常见，且**完全没有权限字符串**）───
        // ⚠️ 这一类是本表存在的核心理由，值得单独说明：
        //    `new Socket(...)` / `URL.openConnection()` 的调用点常在 java.net / okhttp / okio，
        //    字节码里不会出现 "android.permission.INTERNET" 字样。
        //    只扫字符串的话，一个纯网络 SDK（如 OkHttp）会被判成「不使用任何权限」——
        //    但它的 INTERNET 是**必须保留**的，删了 App 直接断网。
        //    这类漏报的后果是「删掉一个真正需要的权限」，属最危险的误判方向。
        put("java/net/Socket#<init>", listOf("INTERNET"))
        put("java/net/Socket#connect", listOf("INTERNET"))
        put("java/net/URL#openConnection", listOf("INTERNET"))
        put("java/net/HttpURLConnection#connect", listOf("INTERNET"))
        put("java/net/HttpURLConnection#getInputStream", listOf("INTERNET"))
        put("java/net/DatagramSocket#<init>", listOf("INTERNET"))
        put("java/net/ServerSocket#<init>", listOf("INTERNET"))
        put("okhttp3/OkHttpClient#newCall", listOf("INTERNET"))
        put("okhttp3/internal/connection/RealCall#execute", listOf("INTERNET"))
        put("okhttp3/internal/connection/RealCall#enqueue", listOf("INTERNET"))
        put("okio/SocketAsyncTimeout", listOf("INTERNET"))
        // 连接状态：SDK 用它判断「当前有没有网」——被动查询，非危险权限
        put("android/net/ConnectivityManager#getActiveNetworkInfo", listOf("ACCESS_NETWORK_STATE"))
        put("android/net/ConnectivityManager#getActiveNetwork", listOf("ACCESS_NETWORK_STATE"))
        put("android/net/ConnectivityManager#registerDefaultNetworkCallback", listOf("ACCESS_NETWORK_STATE"))
        put("android/net/ConnectivityManager#registerNetworkCallback", listOf("ACCESS_NETWORK_STATE"))
        put("android/net/NetworkInfo#isConnected", listOf("ACCESS_NETWORK_STATE"))
        put("android/net/wifi/WifiManager#isWifiEnabled", listOf("ACCESS_WIFI_STATE"))
        put("android/net/wifi/WifiManager#getWifiState", listOf("ACCESS_WIFI_STATE"))
    }

    /** 查 API 隐含的权限。返回空表示「本表不认识这个 API」，不是「不需要权限」。 */
    fun lookup(owner: String, method: String): List<String> =
        TABLE["$owner#$method"] ?: emptyList()

    fun allPermissionShortNames(): Set<String> = TABLE.values.flatten().toSet()

    /**
     * 危险权限（运行时授权）名单，取自 Android 平台的危险权限。
     *
     * 为什么这个划分很重要：它决定移除建议的**后果等级**。
     *   · 普通权限：装即可用，删掉最坏情况是 SDK 某功能失效
     *   · 危险权限：涉及用户隐私授权弹窗与合规审计，删错的代价大得多，
     *     而且它是「权限治理」真正要处理的对象
     */
    val DANGEROUS: Set<String> = setOf(
        // 日历
        "READ_CALENDAR", "WRITE_CALENDAR",
        // 相机
        "CAMERA",
        // 联系人
        "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS",
        // 位置
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
        // 麦克风
        "RECORD_AUDIO",
        // 电话
        "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS",
        "READ_CALL_LOG", "WRITE_CALL_LOG", "ADD_VOICEMAIL", "USE_SIP", "PROCESS_OUTGOING_CALLS",
        // 身体传感器
        "BODY_SENSORS", "ACTIVITY_RECOGNITION",
        // 短信
        "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_WAP_PUSH", "RECEIVE_MMS",
        // 存储
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE",
        // 蓝牙（12+）
        "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE",
        // 通知（13+ 需运行时申请，属特殊）
        "POST_NOTIFICATIONS",
        // 后台弹出界面等厂商权限按需补充
        "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "QUERY_ALL_PACKAGES",
    )

    fun isDangerous(fullName: String): Boolean =
        DANGEROUS.contains(fullName.removePrefix("android.permission."))
}
