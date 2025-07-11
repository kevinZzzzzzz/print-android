package com.print_android.app

import android.app.PendingIntent
import android.os.Bundle
import android.content.Context
import android.print.PrintManager
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent
import android.provider.MediaStore
import android.app.Activity
import android.graphics.Bitmap
import android.util.Base64
import android.graphics.BitmapFactory
import java.io.*
import android.net.Uri
import android.content.ContentResolver
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import android.os.Environment
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.provider.Settings
import java.nio.charset.StandardCharsets
import android.print.PrintJobInfo

/**
 * MainActivity 类
 * 主要功能：
 * 1. USB打印机连接和打印控制
 * 2. 相机拍照功能
 * 3. 文件处理
 */
class MainActivity : TauriActivity() {
    private var currentPhotoPath: String? = null
    private var isPhotoInProgress: Boolean = false
    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    private val USB_PERMISSION_REQUEST_CODE = 101

    /**
     * 日志记录列表和添加日志的方法
     * @param tag 日志标签
     * @param message 日志消息内容
     */
    private val logs = mutableListOf<String>()
    private fun addLog(tag: String, message: String) {
        logs.add("[$tag] $message")
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            handleCameraResult()
        } else {
            Log.d("TakePhoto", "Camera cancelled or failed")
            currentPhotoPath = null
            isPhotoInProgress = false
        }
    }
    
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null

    // 获取USB管理器
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val printManager by lazy { getSystemService(Context.PRINT_SERVICE) as PrintManager }
    private val deviceList by lazy { usbManager.deviceList }

    // 获取目标打印机设备
    private var devicePath: String? = null
    private var targetDevice: UsbDevice? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    /**
     * 获取已连接的USB设备列表
     * @return 返回JSON格式的设备信息列表，包含设备名称、厂商ID、产品ID等信息
     */
    @JvmName("getConnectedUsbDevices")
    fun getConnectedUsbDevices(): String {
        // 打印输出日志到终端
        addLog("USB", "getConnectedUsbDevices called")
        try {
            addLog("USB", "UsbDevices getConnectedUsbDevices called")
      
            val devicesArray = JSONArray()
            // 打印devicesArray
            addLog("USB", "UsbDevices devicesArray: $devicesArray")
            
            for (device in deviceList.values) {
                
                // 判断manufacturer_name 是 HP 开头的
                if (device.manufacturerName?.startsWith("HP") == true) {
                    val permissionIntent = PendingIntent.getBroadcast(
                        this, 0,
                        Intent("com.usb.printer.USB_PERMISSION"),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    usbManager.requestPermission(device, permissionIntent)
                    val deviceInfo = JSONObject().apply {
                        put("device_name", device.deviceName)
                        put("vendor_id", device.vendorId)
                        put("product_id", device.productId)
                        put("device_class", device.deviceClass)
                        put("device_protocol", device.deviceProtocol)
                        put("manufacturer_name", device.manufacturerName ?: "")
                        put("product_name", device.productName ?: "")
                        put("serial_number", device.serialNumber ?: "")
                    }
                    devicesArray.put(deviceInfo)
                }
            }
            
            addLog("USB", "UsbDevices  Found ${devicesArray.length()} USB devices")
            return devicesArray.toString()
        } catch (e: Exception) {
            addLog("USB", "UsbDevices  Error getting USB devices: ${e.message}")
            return "[]"
        }
    }

    /**
     * 拍照并返回Base64编码的图片数据
     * @return 返回JSON格式的拍照结果，包含状态信息或错误信息
     */
    @JvmName("takePhotoBase64")
    fun takePhotoBase64(): String {
        try {
            // 检查相机权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
                
                // 请求权限
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST_CODE
                )
                
                return createErrorResult("需要相机权限，请重新尝试")
            }
            
            // 创建图片文件
            val photoFile = createImageFile()
            return if (photoFile != null) {
                currentPhotoPath = photoFile.absolutePath
                
                val photoURI: Uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    photoFile
                )
                
                // 启动相机Intent
                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { intent ->
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                }
                
                // 标记拍照正在进行
                isPhotoInProgress = true
                
                takePictureLauncher.launch(takePictureIntent)
                
                // 返回状态信息，告诉前端相机已启动
                val result = JSONObject().apply {
                    put("path", "")
                    put("uri", "")
                    put("status", "camera_launched")
                    put("message", "相机已启动，请拍照")
                }
                result.toString()
            } else {
                createErrorResult("无法创建图片文件")
            }
            
        } catch (e: Exception) {
            Log.e("TakePhoto", "Error starting camera: ${e.message}")
            return createErrorResult("启动相机失败: ${e.message}")
        }
    }
    
    /**
     * 获取拍照结果
     * @return 返回JSON格式的拍照结果，包含图片的Base64编码或状态信息
     */
    @JvmName("getPhotoResult")
    fun getPhotoResult(): String {
        return if (isPhotoInProgress) {
            // 还在拍照中
            val result = JSONObject().apply {
                put("path", "")
                put("uri", "")
                put("status", "in_progress")
                put("message", "正在拍照中...")
            }
            result.toString()
        } else {
            // 检查是否有拍照结果
            currentPhotoPath?.let { path ->
                if (File(path).exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(path)
                        bitmap?.let { bmp ->
                            // 压缩图片以减小size
                            val resizedBitmap = resizeBitmap(bmp, 800, 600)
                            
                            // 转换为base64
                            val byteArrayOutputStream = ByteArrayOutputStream()
                            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
                            val byteArray = byteArrayOutputStream.toByteArray()
                            val base64String = Base64.encodeToString(byteArray, Base64.DEFAULT)
                            
                            Log.d("TakePhoto", "Photo ready, base64 length: ${base64String.length}")
                            
                            // 清理路径，因为照片已经处理完成
                            currentPhotoPath = null
                            
                            val result = JSONObject().apply {
                                put("path", path)
                                put("uri", "data:image/jpeg;base64,$base64String")
                                put("status", "completed")
                                put("message", "拍照完成")
                            }
                            result.toString()
                        } ?: createErrorResult("无法解码图片")
                    } catch (e: Exception) {
                        Log.e("TakePhoto", "Error processing photo: ${e.message}")
                        createErrorResult("处理图片失败: ${e.message}")
                    }
                } else {
                    createErrorResult("图片文件不存在")
                }
            } ?: createErrorResult("没有拍照任务")
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
        private const val ACTION_USB_PERMISSION = "com.print_android.app.USB_PERMISSION"
    }
    /**
     * 执行打印操作
     * @param device_path USB设备路径
     * @return 返回JSON格式的打印结果和日志
     */
    @JvmName("print")
    fun print(device_path: String): String {
        logs.clear()
        var result: String? = null
        var connection: UsbDeviceConnection? = null
        var usbInterface: UsbInterface? = null
        var outEndpoint: UsbEndpoint? = null
        var inEndpoint: UsbEndpoint? = null

        try {
            // 使用标志变量控制流程,避免提前返回
            var shouldContinue = true
            
            // 检查权限
            if (shouldContinue && !checkStoragePermissions()) {
                result = createJsonResponse("需要存储权限，请授权后重试", logs)
                shouldContinue = false
            }

            // 读取文件
            var fileBytes = ByteArray(0)
            if (shouldContinue) {
                try {
                    val file = File(Environment.getExternalStorageDirectory(), "test.pdf")
                    if (!file.exists()) {
                        result = createJsonResponse("文件不存在：${file.absolutePath}", logs)
                        shouldContinue = false
                    } else {
                        fileBytes = FileInputStream(file).use { it.readBytes() }
                        addLog("FILE", "成功读取文件，大小: ${fileBytes.size} 字节")
                    }
                } catch (e: Exception) {
                    result = createJsonResponse("读取文件失败: ${e.message}", logs)
                    shouldContinue = false
                }
            }

            // 初始化USB设备
            if (shouldContinue) {
                devicePath = device_path
                targetDevice = deviceList.values.find { it.deviceName == devicePath }
                if (targetDevice == null) {
                    result = createJsonResponse("未找到指定的打印机设备：$devicePath", logs)
                    shouldContinue = false
                } else {
                    addLog("DEVICE", "找到打印机设备: ${targetDevice?.deviceName}")
                }
            }

            // 检查USB权限
            if (shouldContinue && !usbManager.hasPermission(targetDevice)) {
                val permissionIntent = PendingIntent.getBroadcast(
                    this, 
                    0, 
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                usbManager.requestPermission(targetDevice, permissionIntent)
                result = createJsonResponse("需要USB设备权限，请授权后重试", logs)
                shouldContinue = false
            }

            // 设置USB连接
            if (shouldContinue) {
                usbInterface = targetDevice?.getInterface(0)
                outEndpoint = findEndpoint(usbInterface!!, UsbConstants.USB_DIR_OUT)
                inEndpoint = findEndpoint(usbInterface!!, UsbConstants.USB_DIR_IN)

                if (outEndpoint == null || inEndpoint == null) {
                    result = createJsonResponse("未找到合适的USB端点", logs)
                    shouldContinue = false
                }
            }

            // 打开连接
            if (shouldContinue) {
                connection = usbManager.openDevice(targetDevice)
                if (connection == null) {
                    result = createJsonResponse("无法连接到打印机设备", logs)
                    shouldContinue = false
                } else if (!connection.claimInterface(usbInterface, true)) {
                    result = createJsonResponse("无法获取USB接口控制权", logs)
                    shouldContinue = false
                }
            }

            // 执行打印
            if (shouldContinue) {
              doPrint(connection!!, outEndpoint!!, inEndpoint!!, fileBytes)
            }

        } catch (e: Exception) {
            addLog("ERROR", "打印过程出错: ${e.message}")
            result = createJsonResponse("打印失败: ${e.message}", logs)
        } finally {
            addLog("CLEANUP", "开始清理资源")
            
            try {
                // 1. 强制停止所有打印操作
                if (connection != null && outEndpoint != null) {
                    try {
                        // 发送多个取消和重置命令
                        val cancelCommands = listOf(
                            "\u0018",        // CAN - 取消当前任务
                            "\u001B\u0001",  // ESC SOH - 软复位
                            "\u001B@",       // ESC @ - 初始化打印机
                            "@PJL RESET\r\n",
                            "@PJL CANCEL\r\n",
                            "@PJL ABORT\r\n",
                            "@PJL EOJ\r\n",
                            "@PJL ENTER LANGUAGE=PCL\r\n",
                            "\u001BE",       // ESC E - 重置打印机
                            "\u001Bx\u0001"  // 复位打印机
                        )

                        for (cmd in cancelCommands) {
                            val cmdBytes = cmd.toByteArray(StandardCharsets.US_ASCII)
                            connection.bulkTransfer(outEndpoint, cmdBytes, cmdBytes.size, 1000)
                            Thread.sleep(200)  // 增加等待时间确保命令被执行
                        }
                        addLog("CLEANUP", "发送取消和重置命令成功")
                    } catch (e: Exception) {
                        addLog("ERROR", "发送取消命令失败: ${e.message}")
                    }
                }

                // 2. 清理系统打印队列
                try {
                    // 多次尝试清理打印队列
                    repeat(3) {
                        clearSystemPrintJobs()
                        Thread.sleep(500)  // 等待打印队列更新
                    }
                    addLog("CLEANUP", "清理打印队列成功")
                } catch (e: Exception) {
                    addLog("ERROR", "清理打印队列失败: ${e.message}")
                }

                // 3. 释放USB接口
                if (connection != null && usbInterface != null) {
                    try {
                        // 在释放接口之前再次发送重置命令
                        val finalResetCmd = "\u001B@".toByteArray(StandardCharsets.US_ASCII)
                        connection.bulkTransfer(outEndpoint, finalResetCmd, finalResetCmd.size, 1000)
                        Thread.sleep(200)
                        
                        connection.releaseInterface(usbInterface)
                        addLog("CLEANUP", "释放接口成功")
                    } catch (e: Exception) {
                        addLog("ERROR", "释放接口失败: ${e.message}")
                    }
                }

                // 4. 关闭USB连接
                if (connection != null) {
                    try {
                        connection.close()
                        addLog("CLEANUP", "关闭连接成功")
                    } catch (e: Exception) {
                        addLog("ERROR", "关闭连接失败: ${e.message}")
                    }
                }

                // 5. 等待一段时间确保所有操作都已完成
                Thread.sleep(1000)
                
            } catch (e: Exception) {
                addLog("ERROR", "清理资源时发生错误: ${e.message}")
            } finally {
                // 确保所有资源都被释放
                connection = null
                usbInterface = null
                outEndpoint = null
                inEndpoint = null
                devicePath = null
                targetDevice = null
                addLog("END", "所有资源清理完成")
            }
        }

        // 在返回结果之前再次确保打印队列为空
        try {
            clearSystemPrintJobs()
        } catch (e: Exception) {
            addLog("WARN", "最终清理打印队列时出错: ${e.message}")
        }

        return result ?: createJsonResponse("打印完成", logs)
    }

/**
 * 查找指定方向的USB端点
 * @param intf USB接口
 * @param direction 端点方向（输入/输出）
 * @return 返回找到的USB端点，如果未找到则返回null
 */
private fun findEndpoint(intf: UsbInterface, direction: Int): UsbEndpoint? {
    return (0 until intf.endpointCount)
        .map { intf.getEndpoint(it) }
        .find { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == direction }
}

/**
 * 检查打印机状态
 * @param connection USB连接
 * @param outEndpoint 输出端点
 * @param inEndpoint 输入端点
 * @return 返回打印机状态字符串
 */
private fun checkPrinterStatus(
    connection: UsbDeviceConnection,
    outEndpoint: UsbEndpoint,
    inEndpoint: UsbEndpoint
): String {
    try {
        // 使用简单的ESC命令查询状态
        val queryCmd = "\u001B*s1M".toByteArray(StandardCharsets.US_ASCII)
        connection.bulkTransfer(outEndpoint, queryCmd, queryCmd.size, 1000)
        
        Thread.sleep(200) // 等待打印机响应
        
        val response = ByteArray(8)
        val bytesRead = connection.bulkTransfer(inEndpoint, response, response.size, 1000)
        
        return if (bytesRead > 0) {
            "PRINTER_RESPONDING"
        } else {
            "CONTINUE_PRINTING"
        }
    } catch (e: Exception) {
        addLog("STATUS_ERROR", "状态查询异常: ${e.message}")
        return "CONTINUE_PRINTING"
    }
}

/**
 * 清理系统打印队列
 * 取消所有未完成的打印任务
 */
private fun clearSystemPrintJobs() {
    addLog("CLEAR", "开始清理系统打印队列")
    val printManager = this.getSystemService(Context.PRINT_SERVICE) as PrintManager
    addLog("CLEAR", "获取打印服务, ${printManager}, ${printManager.printJobs.size}")
    
    printManager.printJobs.forEach { job ->
        when (job.info.state) {
            PrintJobInfo.STATE_CREATED,
            PrintJobInfo.STATE_QUEUED,
            PrintJobInfo.STATE_STARTED,
            PrintJobInfo.STATE_BLOCKED -> {
                job.cancel()
                addLog("CLEAR", "取消打印作业: ${job.info.id}")
            }
        }
    }
}
    
    /**
     * 创建临时图片文件
     * @return 返回创建的临时文件，如果创建失败则返回null
     */
    private fun createImageFile(): File? {
        return try {
            val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            File.createTempFile(
                "JPEG_${timeStamp}_",
                ".jpg",
                storageDir
            )
        } catch (ex: IOException) {
            Log.e("TakePhoto", "Error creating image file: ${ex.message}")
            null
        }
    }
    
    /**
     * 处理相机拍照结果
     * 标记拍照过程完成
     */
    private fun handleCameraResult() {
        Log.d("TakePhoto", "Camera result received, photo path: $currentPhotoPath")
        isPhotoInProgress = false // 标记拍照完成
    }
    
    /**
     * 调整图片大小
     * @param bitmap 原始图片
     * @param maxWidth 最大宽度
     * @param maxHeight 最大高度
     * @return 返回调整大小后的图片
     */
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val scaleWidth = maxWidth.toFloat() / width
        val scaleHeight = maxHeight.toFloat() / height
        val scale = kotlin.math.min(scaleWidth, scaleHeight)
        
        if (scale >= 1f) {
            return bitmap
        }
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * 创建错误响应JSON
     * @param errorMessage 错误信息
     * @return 返回JSON格式的错误信息
     */
    private fun createErrorResult(errorMessage: String): String {
        val errorResult = JSONObject().apply {
            put("path", "")
            put("uri", "")
            put("error", errorMessage)
        }
        return errorResult.toString()
    }
    /**
     * 处理权限请求结果
     * @param requestCode 请求码
     * @param permissions 权限数组
     * @param grantResults 授权结果数组
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("TakePhoto", "Camera permission granted")
                } else {
                    Log.d("TakePhoto", "Camera permission denied")
                    isPhotoInProgress = false
                }
            }
        }
    }

    /**
     * 创建JSON响应
     * @param message 响应消息
     * @param logs 日志列表
     * @return 返回JSON格式的响应数据
     */
    private fun createJsonResponse(message: String, logs: List<String>): String {
        return JSONObject().apply {
            put("message", message)
            put("logs", JSONArray(logs))
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    /**
     * 执行打印操作的核心方法
     * @param connection USB连接
     * @param outEndpoint 输出端点
     * @param inEndpoint 输入端点
     * @param fileBytes 要打印的文件数据
     */
    private fun doPrint(
        connection: UsbDeviceConnection,
        outEndpoint: UsbEndpoint,
        inEndpoint: UsbEndpoint,
        fileBytes: ByteArray
    ) {
        val start = System.currentTimeMillis()
        addLog("PRINT", "开始打印")

        // 1. 初始化打印机
        val initCommands = listOf(
            byteArrayOf(0x1B, 0x40),  // ESC @
            "@PJL RESET\r\n",
            "@PJL JOB NAME=\"PrintJob\"\r\n",
            "@PJL ENTER LANGUAGE=PCL\r\n"
        )

        for (cmd in initCommands) {
            val cmdBytes = when (cmd) {
                is ByteArray -> cmd
                is String -> cmd.toByteArray(StandardCharsets.US_ASCII)
                else -> continue
            }
            if (connection.bulkTransfer(outEndpoint, cmdBytes, cmdBytes.size, 1000) < 0) {
                addLog("ERROR", "打印机初始化失败")
                return
            }
            Thread.sleep(100)
        }

        // 2. 分块传输数据
        val CHUNK_SIZE = outEndpoint.maxPacketSize
        var offset = 0

        while (offset < fileBytes.size) {
            val endIdx = minOf(offset + CHUNK_SIZE, fileBytes.size)
            val chunk = fileBytes.copyOfRange(offset, endIdx)
            
            val result = connection.bulkTransfer(outEndpoint, chunk, chunk.size, 1000)
            if (result < 0) {
                addLog("ERROR", "数据传输失败，已发送 $offset 字节")
                return
            }
            
            offset += chunk.size
            addLog("PRINT", "已发送: $offset / ${fileBytes.size} 字节")

            // 定期检查打印机状态
            if (offset % (CHUNK_SIZE * 100) == 0) {
                checkSimplePrinterStatus(connection, outEndpoint, inEndpoint)
            }
        }

        // 3. 结束打印作业
        val endCommands = listOf(
            "\u000C",        // Form Feed - 强制输出当前页
            "@PJL EOJ\r\n",
            "@PJL RESET\r\n",
            "\u001B\u0045",  // 初始化打印机
            "\u001B@"        // 初始化打印机
        )

        for (cmd in endCommands) {
            val cmdBytes = cmd.toByteArray(StandardCharsets.US_ASCII)
            connection.bulkTransfer(outEndpoint, cmdBytes, cmdBytes.size, 1000)
            Thread.sleep(200)  // 增加延迟时间
        }

        // 4. 等待打印机完成并检查状态
        addLog("WAIT", "等待打印机完成打印...")
        var waitCount = 0
        val maxWait = 30 // 最多等待30次，每次1秒
        
        while (waitCount < maxWait) {
            Thread.sleep(1000)
            val status = checkDetailedPrinterStatus(connection, outEndpoint, inEndpoint)
            addLog("STATUS", "打印机状态检查 $waitCount: $status")
            
            if (status.contains("READY") || status.contains("IDLE")) {
                addLog("COMPLETE", "打印机已完成打印")
                break
            }
            waitCount++
        }

        // 5. 最终强制重置
        val finalResetCommands = listOf(
            "\u0018",        // CAN - 取消命令
            "\u001B\u0045",  // 初始化
            "@PJL RESET\r\n",
            "@PJL ENTER LANGUAGE=PJL\r\n",
            "@PJL COMMENT 清理完成\r\n"
        )

        for (cmd in finalResetCommands) {
            val cmdBytes = cmd.toByteArray(StandardCharsets.US_ASCII)
            connection.bulkTransfer(outEndpoint, cmdBytes, cmdBytes.size, 1000)
            Thread.sleep(100)
        }

        // 6. 清理打印队列
        clearSystemPrintJobs()

        val end = System.currentTimeMillis()
        addLog("TIME", "打印耗时: ${end - start} ms")
        addLog("SUCCESS", "打印数据已成功发送到打印机")
    }

    /**
     * 简单的打印机状态检查
     * @param connection USB连接
     * @param outEndpoint 输出端点
     * @param inEndpoint 输入端点
     */
    private fun checkSimplePrinterStatus(
        connection: UsbDeviceConnection,
        outEndpoint: UsbEndpoint,
        inEndpoint: UsbEndpoint
    ) {
        try {
            val queryCmd = "\u001B*s1M".toByteArray(StandardCharsets.US_ASCII)
            val sendResult = connection.bulkTransfer(outEndpoint, queryCmd, queryCmd.size, 1000)
            
            if (sendResult >= 0) {
                Thread.sleep(100)
                val response = ByteArray(8)
                val bytesRead = connection.bulkTransfer(inEndpoint, response, response.size, 1000)
                if (bytesRead > 0) {
                    addLog("STATUS", "状态查询成功")
                }
            }
        } catch (e: Exception) {
            addLog("STATUS_ERROR", "状态查询异常: ${e.message}")
        }
    }

    /**
     * 详细的打印机状态检查
     * @param connection USB连接
     * @param outEndpoint 输出端点
     * @param inEndpoint 输入端点
     * @return 返回打印机详细状态
     */
    private fun checkDetailedPrinterStatus(
        connection: UsbDeviceConnection,
        outEndpoint: UsbEndpoint,
        inEndpoint: UsbEndpoint
    ): String {
        try {
            // 发送状态查询命令
            val statusQueries = listOf(
                "@PJL INFO STATUS\r\n",
                "@PJL INQUIRE READY\r\n",
                "\u001B*s1M"  // ESC 状态查询
            )
            
            for (query in statusQueries) {
                val queryBytes = query.toByteArray(StandardCharsets.US_ASCII)
                val sendResult = connection.bulkTransfer(outEndpoint, queryBytes, queryBytes.size, 1000)
                
                if (sendResult >= 0) {
                    Thread.sleep(200)
                    val response = ByteArray(64)
                    val bytesRead = connection.bulkTransfer(inEndpoint, response, response.size, 2000)
                    
                    if (bytesRead > 0) {
                        val responseStr = String(response, 0, bytesRead, StandardCharsets.US_ASCII)
                        addLog("STATUS_RESPONSE", "状态响应: $responseStr")
                        
                        // 检查是否包含就绪状态
                        if (responseStr.contains("READY", ignoreCase = true) || 
                            responseStr.contains("IDLE", ignoreCase = true) ||
                            responseStr.contains("ONLINE", ignoreCase = true)) {
                            return "READY"
                        }
                        
                        if (responseStr.contains("BUSY", ignoreCase = true) ||
                            responseStr.contains("PRINTING", ignoreCase = true)) {
                            return "BUSY"
                        }
                        
                        return responseStr.trim()
                    }
                }
            }
            
            return "NO_RESPONSE"
        } catch (e: Exception) {
            addLog("STATUS_ERROR", "状态查询异常: ${e.message}")
            return "ERROR"
        }
    }

    /**
     * 检查存储权限
     * @return 返回是否具有存储权限
     */
    private fun checkStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
} 