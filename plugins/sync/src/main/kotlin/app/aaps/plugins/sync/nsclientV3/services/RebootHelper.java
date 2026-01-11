/**
 * 文件名: RebootHelper.java
 * 描述: 工具类，实现系统重启功能
 *
 * 注意:
 *  - 调用该方法需要应用拥有android.permission.REBOOT权限。
 *  - 只有系统应用或者在root权限环境下才能实际执行重启操作。
 */
package app.aaps.plugins.sync.nsclientV3.services;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

public class RebootHelper {
    private static final String TAG = "RebootHelper";

    /**
     * 执行系统重启
     *
     * @param context 上下文
     * @param reason  重启原因，可以设置为"reboot"或其他自定义字符串
     */
    public static void rebootDevice(Context context, String reason) {
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                // 执行重启操作
                powerManager.reboot(reason);
            } else {
                Log.e(TAG, "无法获取PowerManager服务");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "重启操作失败，权限不足: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "重启操作异常: " + e.getMessage());
        }
    }
}