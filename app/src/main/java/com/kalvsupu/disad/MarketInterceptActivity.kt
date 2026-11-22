package com.kalvsupu.disad

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
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