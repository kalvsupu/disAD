package com.baidukiller.lite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.baidukiller.lite.vpn.AdBlockVpnService

/**
 * ============================================================================
 *  开机自启（可选，默认关闭）
 * ============================================================================
 *  只有当用户已经授予过 VPN 权限（VpnService.prepare 返回 null）时才会自动启动，
 *  绝不会在后台弹出授权窗口骚扰用户。
 * ============================================================================
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val ctx = context.applicationContext
        if (!Prefs.bootAutoStart(ctx)) return

        if (VpnService.prepare(ctx) != null) {
            Log.i("disAD", "开机自启已跳过：尚未获得 VPN 授权")
            return
        }
        try {
            AdBlockVpnService.start(ctx)
            Log.i("disAD", "开机自启：拦截服务已启动")
        } catch (t: Throwable) {
            Log.w("disAD", "开机自启失败：${t.message}")
        }
    }
}
