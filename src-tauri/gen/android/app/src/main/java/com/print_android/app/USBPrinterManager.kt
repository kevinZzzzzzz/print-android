package com.print_android.app;

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.nio.charset.StandardCharsets
import android.print.PrintManager
import android.print.PrintJobInfo

class USBPrinterManager(private val context: Context) {
    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }
    private lateinit var outEndpoint: UsbEndpoint
    private lateinit var usbInterface: UsbInterface
    private var usbDeviceConnection: UsbDeviceConnection? = null
    // private val ACTION_USB_PERMISSION = "${context.packageName}.USB_PERMISSION"
    private val ACTION_USB_PERMISSION = "com.print_android.app.USB_PERMISSION"

    // 1. 初始化USB打印机连接
    fun initUSBPrinter(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    // 2. 注册USB权限广播
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectToDevice(it) }
                    }
                }
            }
        }
    }

    // 3. 连接USB设备并查找端点
    private fun connectToDevice(device: UsbDevice) {
        usbInterface = device.getInterface(0)
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT
            ) {
                outEndpoint = ep
                break
            }
        }

        usbDeviceConnection = usbManager.openDevice(device)
        usbDeviceConnection?.claimInterface(usbInterface, true)
    }

    // 4. 打印核心方法（解决状态残留问题）
    fun printPDF(fileBytes: ByteArray): Boolean {
        try {
            // 关键步骤1：发送惠普复位指令
            val resetCmd = "@PJL DEFAULT RESET=ALL\n".toByteArray(StandardCharsets.US_ASCII)
            usbDeviceConnection?.bulkTransfer(outEndpoint, resetCmd, resetCmd.size, 5000)
            Thread.sleep(1000) // 等待复位完成

            // 关键步骤2：分块传输数据
            val CHUNK_SIZE = outEndpoint.maxPacketSize
            var offset = 0
            while (offset < fileBytes.size) {
                val endIdx = minOf(offset + CHUNK_SIZE, fileBytes.size)
                val chunk = fileBytes.copyOfRange(offset, endIdx)
                val result = usbDeviceConnection?.bulkTransfer(outEndpoint, chunk, chunk.size, 10000)
                if (result == -1) return false // 传输失败
                offset += chunk.size
            }

            // 关键步骤3：发送结束指令
            val endCmd = byteArrayOf(0x0C) // 换页符
            usbDeviceConnection?.bulkTransfer(outEndpoint, endCmd, endCmd.size, 1000)
            
            // 惠普专用作业结束指令
            val jobEndCmd = "@PJL EOJ\n".toByteArray(StandardCharsets.US_ASCII)
            usbDeviceConnection?.bulkTransfer(outEndpoint, jobEndCmd, jobEndCmd.size, 1000)

            // 关键步骤4：等待打印机处理（3秒）
            Thread.sleep(3000)

            // 关键步骤5：清空系统打印队列
            clearSystemPrintJobs()

            return true
        } catch (e: Exception) {
            return false
        }
    }

    // 5. 清空系统打印队列
    private fun clearSystemPrintJobs() {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.printJobs.forEach { job ->
            if (job.info.state == PrintJobInfo.STATE_STARTED || 
                job.info.state == PrintJobInfo.STATE_QUEUED
            ) {
                job.cancel() // 强制取消任务[1,4](@ref)
            }
        }
    }

    // 6. 释放资源
    fun release() {
        usbDeviceConnection?.releaseInterface(usbInterface)
        usbDeviceConnection?.close()
        context.unregisterReceiver(usbPermissionReceiver)
    }

    // 7. 注册广播（在Activity的onStart调用）
    fun registerReceivers() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        context.registerReceiver(usbPermissionReceiver, filter)
    }
}