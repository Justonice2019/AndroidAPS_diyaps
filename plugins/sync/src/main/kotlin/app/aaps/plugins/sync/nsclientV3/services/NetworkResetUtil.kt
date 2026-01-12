package app.aaps.utils

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkResetUtil @Inject constructor(
    private val context: Context
) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    companion object {
        private const val TAG = "NetworkResetUtil"
    }

    /**
     * 重置网络连接 - 主要方法
     */
    fun resetNetworkConnection(): Boolean {
        var success = true
        try {
            Log.d(TAG, "开始重置网络连接")

            // 1. 关闭并重新开启WiFi
            val wifiResetSuccess = resetWiFi()
            Log.d(TAG, "WiFi重置${if (wifiResetSuccess) "成功" else "失败"}")

            // 2. 重置移动网络连接
            val mobileResetSuccess = resetMobileNetwork()
            Log.d(TAG, "移动网络重置${if (mobileResetSuccess) "成功" else "失败"}")

            // 3. 请求网络重新评估
            val reevaluationSuccess = requestNetworkReevaluation()
            Log.d(TAG, "网络重新评估${if (reevaluationSuccess) "成功" else "失败"}")

            success = wifiResetSuccess || mobileResetSuccess || reevaluationSuccess
            Log.d(TAG, "网络重置总体${if (success) "成功" else "失败"}")
        } catch (e: Exception) {
            Log.e(TAG, "网络重置过程中发生异常", e)
            success = false
        }

        return success
    }

    /**
     * 重置WiFi连接
     */
    private fun resetWiFi(): Boolean {
        return try {
            Log.d(TAG, "开始重置WiFi连接")

            // 检查是否有修改WiFi状态的权限
            if (!wifiManager.isWifiEnabled) {
                Log.d(TAG, "WiFi当前未启用，尝试启用")
                wifiManager.isWifiEnabled = true
                Thread.sleep(2000) // 等待2秒让WiFi完全启动
            }

            val wasWifiEnabled = wifiManager.isWifiEnabled
            Log.d(TAG, "WiFi当前状态: ${if (wasWifiEnabled) "已启用" else "已禁用"}")

            // 关闭WiFi
            Log.d(TAG, "关闭WiFi")
            wifiManager.isWifiEnabled = false
            Thread.sleep(2000) // 等待2秒

            // 重新开启WiFi
            Log.d(TAG, "重新开启WiFi")
            wifiManager.isWifiEnabled = wasWifiEnabled
            Thread.sleep(2000) // 等待2秒让WiFi完全启动

            Log.d(TAG, "WiFi重置完成")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "没有修改WiFi状态的权限", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "WiFi重置过程中发生异常", e)
            false
        }
    }

    /**
     * 重置移动网络连接
     */
    private fun resetMobileNetwork(): Boolean {
        return try {
            Log.d(TAG, "开始重置移动网络连接")

            val hasWriteSettingsPermission = android.provider.Settings.System.canWrite(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 9.0+ 使用新API
                Log.d(TAG, "使用Android 9.0+ API重置移动网络")

                // 首先尝试使用NetworkRequest API
                var resetSuccess = resetMobileNetworkWithNetworkRequest()

                // 如果NetworkRequest方法失败，且有权限，尝试使用飞行模式方法
                if (!resetSuccess && hasWriteSettingsPermission) {
                    Log.d(TAG, "NetworkRequest方法失败，尝试使用飞行模式方法")
                    resetSuccess = toggleAirplaneMode()
                } else if (!resetSuccess) {
                    Log.d(TAG, "NetworkRequest方法失败，没有修改系统设置权限，跳过飞行模式方法")
                }

                // 如果飞行模式方法也失败，且有权限，尝试使用通用方法
                if (!resetSuccess && hasWriteSettingsPermission) {
                    Log.d(TAG, "尝试使用通用方法")
                    resetSuccess = resetMobileNetworkGeneric()
                }

                resetSuccess
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1) {
                // Android 8.1 特殊处理
                Log.d(TAG, "使用Android 8.1特殊处理重置移动网络")
                if (hasWriteSettingsPermission) {
                    resetMobileNetworkForOreoMR1()
                } else {
                    Log.d(TAG, "没有修改系统设置权限，跳过Android 8.1特殊处理")
                    false
                }
            } else {
                Log.d(TAG, "使用通用方法重置移动网络")
                if (hasWriteSettingsPermission) {
                    resetMobileNetworkGeneric()
                } else {
                    Log.d(TAG, "没有修改系统设置权限，跳过通用方法")
                    false
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "没有修改网络状态的权限", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "移动网络重置过程中发生异常", e)
            false
        }
    }

    /**
     * Android 8.1 特殊处理方法
     */
    private fun resetMobileNetworkForOreoMR1(): Boolean {
        return try {
            // 检查是否有权限修改系统设置
            if (!android.provider.Settings.System.canWrite(context)) {
                Log.d(TAG, "没有修改系统设置权限，跳过Android 8.1特殊处理")
                return false
            }
            // 尝试通过设置飞行模式来重置移动网络
            toggleAirplaneMode()
        } catch (e: Exception) {
            Log.e(TAG, "Android 8.1移动网络重置失败", e)
            false
        }
    }

    /**
     * 通用移动网络重置方法
     */
    private fun resetMobileNetworkGeneric(): Boolean {
        return try {
            // 检查是否有权限修改系统设置
            if (!android.provider.Settings.System.canWrite(context)) {
                Log.d(TAG, "没有修改系统设置权限，跳过通用移动网络重置方法")
                return false
            }
            // 尝试通过设置飞行模式来重置移动网络
            toggleAirplaneMode()
        } catch (e: Exception) {
            Log.e(TAG, "通用移动网络重置失败", e)
            false
        }
    }
    /**
     * 使用NetworkRequest API重置移动网络
     */
    private fun resetMobileNetworkWithNetworkRequest(): Boolean {
        return try {
            Log.d(TAG, "使用NetworkRequest API重置移动网络")
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            var callbackExecuted = false
            var networkAvailable = false
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.d(TAG, "移动网络已重新连接")
                    networkAvailable = true
                    callbackExecuted = true
                    try {
                        connectivityManager.unregisterNetworkCallback(this)
                    } catch (e: Exception) {
                        // 忽略异常
                    }
                }

                override fun onUnavailable() {
                    Log.w(TAG, "移动网络不可用")
                    callbackExecuted = true
                    try {
                        connectivityManager.unregisterNetworkCallback(this)
                    } catch (e: Exception) {
                        // 忽略异常
                    }
                }

                override fun onLost(network: android.net.Network) {
                    Log.d(TAG, "移动网络连接丢失")
                }
            }

            // 请求新的网络连接
            connectivityManager.requestNetwork(networkRequest, networkCallback)

            // 等待回调执行，最多等待5秒
            var waitCount = 0
            while (!callbackExecuted && waitCount < 50) {
                Thread.sleep(100)
                waitCount++
            }

            if (!callbackExecuted) {
                Log.w(TAG, "移动网络重置超时")
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                } catch (e: Exception) {
                    // 忽略异常
                }
            }

            networkAvailable
        } catch (e: SecurityException) {
            Log.e(TAG, "没有修改网络状态的权限", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "NetworkRequest API重置移动网络失败", e)
            false
        }
    }

    /**
     * Android 8.1 特殊处理方法
     */
    /**
     * 切换飞行模式
     */
    private fun toggleAirplaneMode(): Boolean {
        return try {
            Log.d(TAG, "开始切换飞行模式")

            // 检查是否有修改系统设置的权限
            if (android.provider.Settings.System.canWrite(context)) {
                // 获取当前飞行模式状态
                val isAirplaneModeOn = android.provider.Settings.Global.getInt(
                    context.contentResolver,
                    android.provider.Settings.Global.AIRPLANE_MODE_ON,
                    0
                ) != 0

                Log.d(TAG, "当前飞行模式状态: ${if (isAirplaneModeOn) "开启" else "关闭"}")

                try {
                    // 切换飞行模式
                    Log.d(TAG, "切换飞行模式状态为: ${if (isAirplaneModeOn) "关闭" else "开启"}")
                    android.provider.Settings.Global.putInt(
                        context.contentResolver,
                        android.provider.Settings.Global.AIRPLANE_MODE_ON,
                        if (isAirplaneModeOn) 0 else 1
                    )

                    // 发送广播通知系统飞行模式状态改变
                    val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                    intent.putExtra("state", !isAirplaneModeOn)
                    context.sendBroadcast(intent)

                    // 等待3秒后恢复
                    Log.d(TAG, "等待3秒让飞行模式状态生效")
                    Thread.sleep(3000)

                    // 恢复原来的飞行模式状态
                    Log.d(TAG, "恢复飞行模式状态为: ${if (isAirplaneModeOn) "开启" else "关闭"}")
                    android.provider.Settings.Global.putInt(
                        context.contentResolver,
                        android.provider.Settings.Global.AIRPLANE_MODE_ON,
                        if (isAirplaneModeOn) 1 else 0
                    )

                    // 再次发送广播
                    val restoreIntent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                    restoreIntent.putExtra("state", isAirplaneModeOn)
                    context.sendBroadcast(restoreIntent)

                    Log.d(TAG, "飞行模式切换完成")
                    true
                } catch (e: SecurityException) {
                    Log.e(TAG, "没有修改飞行模式状态的权限", e)
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "切换飞行模式状态时发生异常", e)
                    // 尝试恢复飞行模式状态
                    try {
                        android.provider.Settings.Global.putInt(
                            context.contentResolver,
                            android.provider.Settings.Global.AIRPLANE_MODE_ON,
                            if (isAirplaneModeOn) 1 else 0
                        )
                        val restoreIntent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                        restoreIntent.putExtra("state", isAirplaneModeOn)
                        context.sendBroadcast(restoreIntent)
                    } catch (restoreEx: Exception) {
                        Log.e(TAG, "恢复飞行模式状态时发生异常", restoreEx)
                    }
                    false
                }
            } else {
                Log.e(TAG, "没有修改系统设置的权限")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "飞行模式切换过程中发生异常", e)
            false
        }
    }

    /**
     * 请求网络重新评估
     */
    private fun requestNetworkReevaluation(): Boolean {
        return try {
            Log.d(TAG, "开始请求网络重新评估")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var callbackExecuted = false
                val networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        Log.d(TAG, "网络重新评估完成")
                        connectivityManager.unregisterNetworkCallback(this)
                        callbackExecuted = true
                    }
                }

                connectivityManager.requestNetwork(
                    NetworkRequest.Builder().build(),
                    networkCallback
                )

                // 等待回调执行，最多等待5秒
                var waitCount = 0
                while (!callbackExecuted && waitCount < 50) {
                    Thread.sleep(100)
                    waitCount++
                }

                if (!callbackExecuted) {
                    Log.w(TAG, "网络重新评估超时")
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                }

                callbackExecuted
            } else {
                Log.d(TAG, "Android版本过低，不支持网络重新评估")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "网络重新评估过程中发生异常", e)
            false
        }
    }

    /**
     * 检查网络连接状态
     */
    fun isNetworkConnected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查网络连接状态时发生异常", e)
            false
        }
    }

    /**
     * 检查移动网络是否可用
     */
    fun isMobileNetworkConnected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE)
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查移动网络连接状态时发生异常", e)
            false
        }
    }

    /**
     * 获取当前网络类型
     */
    fun getNetworkType(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                when {
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "移动网络"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
                    else -> "未知"
                }
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                when (networkInfo?.type) {
                    ConnectivityManager.TYPE_WIFI -> "WiFi"
                    ConnectivityManager.TYPE_MOBILE -> "移动网络"
                    ConnectivityManager.TYPE_ETHERNET -> "以太网"
                    else -> "未知"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取网络类型时发生异常", e)
            "错误"
        }
    }

    /**
     * 获取移动网络信号强度
     */
    fun getMobileSignalStrength(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                    val signalStrength = telephonyManager.signalStrength
                    val level = signalStrength?.level
                    when (level) {
                        0, 1 -> "弱"
                        2, 3 -> "中等"
                        4 -> "强"
                        else -> "未知"
                    }
                } else {
                    "无移动网络"
                }
            } else {
                "不支持"
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取移动网络信号强度时发生异常", e)
            "错误"
        }
    }
}