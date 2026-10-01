package com.baidukiller.lite

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * ============================================================================
 *  应用商店跳转拦截器（可选组件，默认禁用）
 * ============================================================================
 *  原理：本 Activity 在清单里注册了 market:// / samsungapps:// 等 scheme。
 *  当广告调用 startActivity(market://...) 时，只要用户把它选为默认打开方式，
 *  系统就会把这次跳转交给我们 —— 我们弹个提示立刻结束，
 *  应用商店根本不会被拉起来。
 *
 *  为什么默认禁用：它会让本 App 出现在所有 market:// 链接的「打开方式」列表里。
 *  想用就在设置界面打开「拦截应用商店跳转」开关，不需要时关掉即可恢复原样。
 *
 *  使用 android:Theme.NoDisplay：不绘制任何界面，只提示一下就退出，
 *  因此用户看到的是"点了一下，商店没有弹出来"。
 * ============================================================================
 */
class MarketInterceptActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Log.i("disAD", "已拦截应用商店跳转：${intent?.data}")
            Toast.makeText(this, "已拦截广告发起的应用商店跳转", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
        // NoDisplay 主题要求在任何 UI 绘制前结束自己
        finish()
    }
}
