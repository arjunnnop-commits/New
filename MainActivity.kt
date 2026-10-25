package com.yooitachiijod.glassfastboot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: FlashViewModel by viewModels()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> vm.onDetached()
                UsbManager.ACTION_USB_DEVICE_ATTACHED, FlashViewModel.ACTION_PERM -> vm.scan()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(FlashViewModel.ACTION_PERM)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { GlassTheme { Root(vm) } }
    }

    override fun onResume() { super.onResume(); vm.scan() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); vm.scan() }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(receiver) }
}
