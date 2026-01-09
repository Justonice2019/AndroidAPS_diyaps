package app.aaps.plugins.sync.nsclientV3.services

import  android.annotation.SuppressLint
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import java.security.MessageDigest
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.nsclient.NSAlarm
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.core.nssdk.mapper.toNSDeviceStatus
import app.aaps.core.nssdk.mapper.toNSFood
import app.aaps.core.nssdk.mapper.toNSSgvV3
import app.aaps.core.nssdk.mapper.toNSTreatment
import app.aaps.core.interfaces.queue.Callback
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.nsShared.NSAlarmObject
import app.aaps.plugins.sync.nsShared.NsIncomingDataProcessor
import app.aaps.plugins.sync.nsShared.events.EventNSClientUpdateGuiStatus
import app.aaps.plugins.sync.nsclient.data.NSDeviceStatusHandler
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import dagger.android.DaggerService
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException
import javax.inject.Inject
import app.aaps.core.interfaces.logging.UserEntryLogger

@Suppress("SpellCheckingInspection")
class NSClientV3Service : DaggerService() {

    @Inject lateinit var injector: HasAndroidInjector
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var sp: SP
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var nsClientV3Plugin: NSClientV3Plugin
    @Inject lateinit var config: Config
    @Inject lateinit var nsIncomingDataProcessor: NsIncomingDataProcessor
    @Inject lateinit var storeDataForDb: StoreDataForDb
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var nsDeviceStatusHandler: NSDeviceStatusHandler
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var constraintChecker: app.aaps.core.interfaces.constraints.ConstraintsChecker
    @Inject lateinit var uel: UserEntryLogger


    private val disposable = CompositeDisposable()

    private var wakeLock: PowerManager.WakeLock? = null
    private val binder: IBinder = LocalBinder()

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidAPS:NSClientService")
        wakeLock?.acquire()
        initializeWebSockets("onCreate")
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdownWebsockets()
        disposable.clear()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    inner class LocalBinder : Binder() {

        val serviceInstance: NSClientV3Service
            get() = this@NSClientV3Service
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int = START_STICKY

    internal var storageSocket: Socket? = null
    private var alarmSocket: Socket? = null
    internal var socketWeb: Socket? = null
    internal var wsConnected = false

    private fun shutdownWebsockets() {
        storageSocket?.on(Socket.EVENT_CONNECT, onConnectStorage)
        storageSocket?.on(Socket.EVENT_DISCONNECT, onDisconnectStorage)
        storageSocket?.on("create", onDataCreate)
        storageSocket?.on("update", onDataUpdate)
        storageSocket?.on("delete", onDataDelete)
        storageSocket?.disconnect()
        
        alarmSocket?.on(Socket.EVENT_CONNECT, onConnectAlarms)
        alarmSocket?.on(Socket.EVENT_DISCONNECT, onDisconnectAlarm)
        alarmSocket?.on("announcement", onAnnouncement)
        alarmSocket?.on("alarm", onAlarm)
        alarmSocket?.on("urgent_alarm", onUrgentAlarm)
        alarmSocket?.on("clear_alarm", onClearAlarm)
        alarmSocket?.disconnect()

        socketWeb?.on(Socket.EVENT_CONNECT, onConnectWeb)
        socketWeb?.on(Socket.EVENT_DISCONNECT, onDisconnectWeb)
        socketWeb?.on("dataUpdate", onDataUpdateWeb)
        socketWeb?.disconnect()

        wsConnected = false
        storageSocket = null
        alarmSocket = null
    }

    fun getV1apisecrethash(apisecret: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            val hashBytes = digest.digest(apisecret.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
    private val onConnectWeb = Emitter.Listener {
        val socketId = socketWeb?.id() ?: "NULL"
        rxBus.send(EventNSClientNewLog("◄ WS Web", "connected web ID: $socketId"))
        if (socketWeb != null) {
            val authMessage = JSONObject().also {
                it.put("client", "web")
                val hash = getV1apisecrethash(preferences.get(StringKey.NsClientApiSecret))
                it.put("secret", hash)
                // it.put("token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhY2Nlc3NUb2tlbiI6ImFkbWluLTBmOWYzMmNiOGYwYThhYzkiLCJpYXQiOjE3NjYwNjg1NDEsImV4cCI6MTc2NjE1NDk0MX0.PMZMuoEE_tIfJi0AqjyEpCnPCV_2IRCNueVh1hWQCnQ")
                it.put("history", 5)
            }
            socketWeb?.emit("authorize", authMessage, Ack { args ->
                val response = args[0] as JSONObject
                rxBus.send(EventNSClientNewLog("◄ WS Web", "Authorized {read: ${response.optBoolean("read")}, write_treatment: ${response.optBoolean("write_treatment")}} "))
            })
        }
    }
    private val onDisconnectWeb = Emitter.Listener {args ->
        rxBus.send(EventNSClientNewLog("◄ WS Web", "disconnect web event"))
    }
    
    private val onDataUpdateWeb = Emitter.Listener {args ->
        // val data = args[0] as JSONObject
        // rxBus.send(EventNSClientNewLog("◄ WS Web", "dataUpdate $data"))
        //
        // // 处理远程打药请求
        // try {
        //     // 检查是否有treatments数组 (根据示例数据使用treaments拼写)
        //     if (data.has("treatments")) {
        //         val treatments = data.getJSONArray("treatments")
        //         for (i in 0 until treatments.length()) {
        //             val treatment = treatments.getJSONObject(i)
        //             // 检查是否有_insulin字段且值大于0
        //             if (treatment.has("_insulin") && treatment.getDouble("_insulin") > 0) {
        //                 // 调用处理打药的方法
        //                 handleRemoteBolusFromTreatment(treatment)
        //             }
        //         }
        //     }
        // } catch (e: Exception) {
        //     aapsLogger.error(LTag.NSCLIENT, "Error processing remote bolus: ", e)
        //     rxBus.send(EventNSClientNewLog("◄ WS Web", "Error processing remote bolus: ${e.message}"))
        // }
    }
    
    // 处理从treatment对象中提取的打药请求
    private fun handleRemoteBolusFromTreatment(treatment: JSONObject) {
        try {
            // 从treatment对象中获取_insulin字段值
            val bolusAmount = treatment.getDouble("_insulin")
            
            // 记录日志，包含更多上下文信息
            val eventType = treatment.optString("_eventType", "Unknown")
            val drugType = treatment.optString("_drugType", "Unknown")
            rxBus.send(EventNSClientNewLog("◄ WS Web", "Processing insulin from treatment: $bolusAmount U, Type: $drugType, Event: $eventType"))
            
            // 创建打药信息对象
            val detailedBolusInfo = DetailedBolusInfo()
            detailedBolusInfo.insulin = bolusAmount
            
            // 直接执行打药操作
            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                override fun run() {
                    // 记录基本日志
                    val result: PumpEnactResult = result
                    if (result.success) {
                        rxBus.send(EventNSClientNewLog("◄ WS Web", "Remote bolus from treatment completed: ${result.bolusDelivered} U"))
                    } else {
                        rxBus.send(EventNSClientNewLog("◄ WS Web", "Remote bolus from treatment failed"))
                    }
                }
            })
        } catch (e: Exception) {
            // 简单的异常处理
            rxBus.send(EventNSClientNewLog("◄ WS Web", "Error processing treatment bolus: ${e.message}"))
        }
    }

    @Suppress("SameParameterValue")
    private fun initializeWebSockets(reason: String) {
        if (preferences.get(StringKey.NsClientUrl).isEmpty()) return
        val baseUrl = preferences.get(StringKey.NsClientUrl).lowercase().replace(Regex("/$"), "")
        // 根据HTTP/HTTPS协议自动转换为WS/WSS协议
        val wsBaseUrl = when {
            baseUrl.startsWith("https://") -> baseUrl.replace("https://", "wss://")
            baseUrl.startsWith("http://") -> baseUrl.replace("http://", "ws://")
            else -> "wss://$baseUrl" // 默认使用wss
        }
        val urlStorage = wsBaseUrl + "/storage"
        val urlAlarm = wsBaseUrl + "/alarm"
        val url = wsBaseUrl

        socketWeb = IO.socket(url).also {socket ->
            socket.on(Socket.EVENT_CONNECT, onConnectWeb)
            socket.on(Socket.EVENT_DISCONNECT, onDisconnectWeb)
            socket.connect()
            socket.on("dataUpdate", onDataUpdateWeb)
        }

        if (!nsClientV3Plugin.isAllowed) {
            rxBus.send(EventNSClientNewLog("● WS", nsClientV3Plugin.blockingReason))
        } else if (sp.getBoolean(R.string.key_ns_paused, false)) {
            rxBus.send(EventNSClientNewLog("● WS", "paused"))
        } else {
            try {
                // java io.client doesn't support multiplexing. create 2 sockets
                storageSocket = IO.socket(urlStorage).also { socket ->
                    socket.on(Socket.EVENT_CONNECT, onConnectStorage)
                    socket.on(Socket.EVENT_DISCONNECT, onDisconnectStorage)
                    rxBus.send(EventNSClientNewLog("► WS", "do connect storage $reason"))
                    socket.connect()
                    // 使用独立的事件处理器
                    socket.on("create", onDataCreate)
                    socket.on("update", onDataUpdate)
                    socket.on("delete", onDataDelete)
                }
                if (preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements) ||
                    preferences.get(BooleanKey.NsClientNotificationsFromAlarms)
                )
                    alarmSocket = IO.socket(urlAlarm).also { socket ->
                        socket.on(Socket.EVENT_CONNECT, onConnectAlarms)
                        socket.on(Socket.EVENT_DISCONNECT, onDisconnectAlarm)
                        rxBus.send(EventNSClientNewLog("► WS", "do connect alarm $reason"))
                        socket.connect()
                        socket.on("announcement", onAnnouncement)
                        socket.on("alarm", onAlarm)
                        socket.on("urgent_alarm", onUrgentAlarm)
                        socket.on("clear_alarm", onClearAlarm)
                    }
            } catch (_: URISyntaxException) {
                rxBus.send(EventNSClientNewLog("● WS", "Wrong URL syntax"))
            } catch (_: RuntimeException) {
                rxBus.send(EventNSClientNewLog("● WS", "RuntimeException"))
            }
        }
    }

    private val onConnectStorage = Emitter.Listener {
        val socketId = storageSocket?.id() ?: "NULL"
        rxBus.send(EventNSClientNewLog("◄ WS", "connected storage ID: $socketId"))
        if (storageSocket != null) {
            val authMessage = JSONObject().also {
                it.put("accessToken", preferences.get(StringKey.NsClientAccessToken))
                it.put("collections", JSONArray(arrayOf("devicestatus", "entries", "profile", "treatments", "foods", "settings")))
            }
            rxBus.send(EventNSClientNewLog("► WS", "requesting auth for storage"))
            storageSocket?.emit("subscribe", authMessage, Ack { args ->
                val response = args[0] as JSONObject
                wsConnected = if (response.optBoolean("success")) {
                    rxBus.send(EventNSClientNewLog("◄ WS", "Subscribed for: ${response.optString("collections")}"))
                    // during disconnection updated data is not received
                    // thus run non WS load to get missing data
                    nsClientV3Plugin.initialLoadFinished = false
                    nsClientV3Plugin.executeLoop("WS_CONNECT", forceNew = true)
                    true
                } else {
                    rxBus.send(EventNSClientNewLog("◄ WS", "Auth failed"))
                    false
                }
                rxBus.send(EventNSClientUpdateGuiStatus())
            })
        }
    }

    private val onConnectAlarms = Emitter.Listener {
        val socket = alarmSocket
        val socketId = socket?.id() ?: "NULL"
        rxBus.send(EventNSClientNewLog("◄ WS", "connected alarms ID: $socketId"))
        if (socket != null) {
            val authMessage = JSONObject().also {
                it.put("accessToken", preferences.get(StringKey.NsClientAccessToken))
            }
            rxBus.send(EventNSClientNewLog("► WS", "requesting auth for alarms"))
            socket.emit("subscribe", authMessage, Ack { args ->
                val response = args[0] as JSONObject
                if (response.optBoolean("success")) rxBus.send(EventNSClientNewLog("◄ WS", response.optString("message")))
                else rxBus.send(EventNSClientNewLog("◄ WS", "Auth failed"))
            })
        }
    }

    private val onDisconnectStorage = Emitter.Listener { args ->
        aapsLogger.debug(LTag.NSCLIENT, "disconnect storage reason: ${args[0]}")
        rxBus.send(EventNSClientNewLog("◄ WS", "disconnect storage event"))
        wsConnected = false
        nsClientV3Plugin.initialLoadFinished = false
        rxBus.send(EventNSClientUpdateGuiStatus())
    }

    private val onDisconnectAlarm = Emitter.Listener { args ->
        aapsLogger.debug(LTag.NSCLIENT, "disconnect alarm reason: ${args[0]}")
        rxBus.send(EventNSClientNewLog("◄ WS", "disconnect alarm event"))
    }

    // 独立的事件处理器
    private val onDataCreate = Emitter.Listener { args ->
        handleDataOperation(args, "create")
    }

    private val onDataUpdate = Emitter.Listener { args ->
        handleDataOperation(args, "update")
    }

    // 提取公共处理逻辑
    private fun handleDataOperation(args: Array<Any>, operation: String) {
        val response = args[0] as JSONObject
        aapsLogger.debug(LTag.NSCLIENT, "onData${operation.replaceFirstChar { it.uppercase() }}: $response")
        val collection = response.getString("colName")
        val docJson = response.getJSONObject("doc")
        val docString = response.getString("doc")
        rxBus.send(EventNSClientNewLog("◄ WS $operation.uppercase()", "$collection <i>$docString</i>"))
        val srvModified = docJson.getLong("srvModified")
        nsClientV3Plugin.lastLoadedSrvModified.set(collection, srvModified)
        nsClientV3Plugin.storeLastLoadedSrvModified()
        when (collection) {
            "devicestatus" -> docString.toNSDeviceStatus().let { nsDeviceStatusHandler.handleNewData(arrayOf(it)) }
            "entries"      -> docString.toNSSgvV3()?.let {
                nsIncomingDataProcessor.processSgvs(listOf(it), doFullSync = false)
                storeDataForDb.storeGlucoseValuesToDb()
            }

            "profile"      ->
                nsIncomingDataProcessor.processProfile(docJson, doFullSync = false)

            "treatments"   -> docString.toNSTreatment()?.let {
                nsIncomingDataProcessor.processTreatments(listOf(it), doFullSync = false)
                storeDataForDb.storeTreatmentsToDb(fullSync = false)
            }

            "foods"        -> docString.toNSFood()?.let {
                nsIncomingDataProcessor.processFood(listOf(it))
                storeDataForDb.storeFoodsToDb()
            }

            "settings"     -> { /* nothing to do for now */
            }
        }
    }

    private val onDataDelete = Emitter.Listener { args ->
        val response = args[0] as JSONObject
        aapsLogger.debug(LTag.NSCLIENT, "onDataDelete: $response")
        val collection = response.optString("colName") ?: return@Listener
        val identifier = response.optString("identifier") ?: return@Listener
        rxBus.send(EventNSClientNewLog("◄ WS DELETE", "$collection $identifier"))
        if (collection == "treatments") {
            storeDataForDb.addToDeleteTreatment(identifier)
            storeDataForDb.updateDeletedTreatmentsInDb()
        }
        if (collection == "entries") {
            storeDataForDb.addToDeleteGlucoseValue(identifier)
            storeDataForDb.updateDeletedGlucoseValuesInDb()
        }
    }

    private val onAnnouncement = Emitter.Listener { args ->

        /*
        {
        "level":0,
        "title":"Announcement",
        "message":"test",
        "plugin":{"name":"treatmentnotify","label":"Treatment Notifications","pluginType":"notification","enabled":true},
        "group":"Announcement",
        "isAnnouncement":true,
        "key":"9ac46ad9a1dcda79dd87dae418fce0e7955c68da"
        }
         */
        val data = args[0] as JSONObject
        rxBus.send(EventNSClientNewLog("◄ ANNOUNCEMENT", data.optString("message")))
        aapsLogger.debug(LTag.NSCLIENT, data.toString())
        if (preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements))
            uiInteraction.addNotificationWithAction(NSAlarmObject(data))
    }
    private val onAlarm = Emitter.Listener { args ->

        /*
        {
        "level":1,
        "title":"Warning HIGH",
        "message":"BG Now: 5 -0.2 → mmol\/L\nRaw BG: 4.8 mmol\/L Čistý\nBG 15m: 4.8 mmol\/L\nIOB: -0.02U\nCOB: 0g",
        "eventName":"high",
        "plugin":{"name":"simplealarms","label":"Simple Alarms","pluginType":"notification","enabled":true},
        "pushoverSound":"climb",
        "debug":{"lastSGV":5,"thresholds":{"bgHigh":180,"bgTargetTop":75,"bgTargetBottom":72,"bgLow":70}},
        "group":"default",
        "key":"simplealarms_1"
        }
         */
        val data = args[0] as JSONObject
        rxBus.send(EventNSClientNewLog("◄ ALARM", data.optString("message")))
        aapsLogger.debug(LTag.NSCLIENT, data.toString())
        if (preferences.get(BooleanKey.NsClientNotificationsFromAlarms)) {
            val snoozedTo = sp.getLong(rh.gs(app.aaps.core.utils.R.string.key_snoozed_to) + data.optString("level"), 0L)
            if (snoozedTo == 0L || System.currentTimeMillis() > snoozedTo)
                uiInteraction.addNotificationWithAction(NSAlarmObject(data))
        }
    }

    private val onUrgentAlarm = Emitter.Listener { args: Array<Any> ->
        val data = args[0] as JSONObject
        rxBus.send(EventNSClientNewLog("◄ URGENT ALARM", data.optString("message")))
        aapsLogger.debug(LTag.NSCLIENT, data.toString())
        if (preferences.get(BooleanKey.NsClientNotificationsFromAlarms)) {
            val snoozedTo = sp.getLong(rh.gs(app.aaps.core.utils.R.string.key_snoozed_to) + data.optString("level"), 0L)
            if (snoozedTo == 0L || System.currentTimeMillis() > snoozedTo)
                uiInteraction.addNotificationWithAction(NSAlarmObject(data))
        }
    }

    private val onClearAlarm = Emitter.Listener { args ->

        /*
        {
        "clear":true,
        "title":"All Clear",
        "message":"default - Urgent was ack'd",
        "group":"default"
        }
         */
        val data = args[0] as JSONObject
        rxBus.send(EventNSClientNewLog("◄ CLEARALARM", data.optString("title")))
        aapsLogger.debug(LTag.NSCLIENT, data.toString())
        rxBus.send(EventDismissNotification(Notification.NS_ALARM))
        rxBus.send(EventDismissNotification(Notification.NS_URGENT_ALARM))
    }

    fun handleClearAlarm(originalAlarm: NSAlarm, silenceTimeInMilliseconds: Long) {
        alarmSocket?.emit("ack", originalAlarm.level(), originalAlarm.group(), silenceTimeInMilliseconds)
        rxBus.send(EventNSClientNewLog("► ALARMACK ", "${originalAlarm.level()} ${originalAlarm.group()} $silenceTimeInMilliseconds"))
    }
}