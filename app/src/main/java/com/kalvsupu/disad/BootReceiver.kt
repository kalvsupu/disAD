package com.kalvsupu.disad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.kalvsupu.disad.vpn.AdBlockVpnService

/**
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