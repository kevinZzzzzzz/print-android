package com.print_android.app

import android.app.PendingIntent
import android.os.Bundle
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.database.Cursor
import androidx.appcompat.app.AppCompatActivity
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
import java.io.File
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
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintAttributes
import android.print.PageRange
import android.os.ParcelFileDescriptor
import android.print.PrintJob
import android.os.CancellationSignal
import android.print.PrintDocumentAdapter.LayoutResultCallback
import android.print.PrintDocumentAdapter.WriteResultCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import android.print.pdf.PrintedPdfDocument
import android.graphics.pdf.PdfDocument

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

    private var downloadId: Long = -1
    private val downloadManager by lazy { getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }

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
     * 执行静默打印操作（直接USB打印，跳过系统预览）
     * @param device_path USB设备路径
     * @return 返回JSON格式的打印结果和日志
     */
    @JvmName("print")
    fun print(device_path: String, uri: String): String {
        logs.clear()
        var result: String? = null
        var connection: UsbDeviceConnection? = null
        var usbInterface: UsbInterface? = null
        var outEndpoint: UsbEndpoint? = null
        var inEndpoint: UsbEndpoint? = null

        try {
            addLog("SILENT_PRINT", "开始静默打印")
            
            // 检查权限
            if (!checkStoragePermissions()) {
                return createJsonResponse("需要存储权限，请授权后重试", logs)
            }

            // 读取PDF文件
            // val file = File(Environment.getExternalStorageDirectory(), uri)
            // 获取下载完的路径
            val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadPath, uri)
            if (!file.exists()) {
                return createJsonResponse("文件不存在：${file.absolutePath}", logs)
            }

            val fileBytes = FileInputStream(file).use { it.readBytes() }
            addLog("FILE", "成功读取PDF文件，大小: ${fileBytes.size} 字节")

            // 查找USB打印机设备
            devicePath = device_path
            targetDevice = deviceList.values.find { it.deviceName == devicePath }
            if (targetDevice == null) {
                return createJsonResponse("未找到指定的打印机设备：$devicePath", logs)
            }
            addLog("DEVICE", "找到打印机设备: ${targetDevice?.deviceName}")

            // 检查USB权限
            if (!usbManager.hasPermission(targetDevice)) {
                val permissionIntent = PendingIntent.getBroadcast(
                    this, 
                    0, 
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                usbManager.requestPermission(targetDevice, permissionIntent)
                return createJsonResponse("需要USB设备权限，请授权后重试", logs)
            }

            // 设置USB连接
            usbInterface = targetDevice?.getInterface(0)
            outEndpoint = findEndpoint(usbInterface!!, UsbConstants.USB_DIR_OUT)
            inEndpoint = findEndpoint(usbInterface!!, UsbConstants.USB_DIR_IN)

            if (outEndpoint == null || inEndpoint == null) {
                return createJsonResponse("未找到合适的USB端点", logs)
            }

            // 打开USB连接
            connection = usbManager.openDevice(targetDevice)
            if (connection == null) {
                return createJsonResponse("无法连接到打印机设备", logs)
            }
            
            if (!connection.claimInterface(usbInterface, true)) {
                return createJsonResponse("无法获取USB接口控制权", logs)
            }

            // 执行静默打印
            performSilentPrint(connection, outEndpoint, inEndpoint, fileBytes, uri)
            
            addLog("SUCCESS", "静默打印完成")
            result = createJsonResponse("静默打印成功", logs)

        } catch (e: Exception) {
            addLog("ERROR", "静默打印失败: ${e.message}")
            result = createJsonResponse("静默打印失败: ${e.message}", logs)
        } finally {
            // 清理资源
            try {
                if (connection != null && usbInterface != null) {
                    connection.releaseInterface(usbInterface)
                    connection.close()
                    addLog("CLEANUP", "USB连接已清理")
                }
            } catch (e: Exception) {
                addLog("ERROR", "清理资源失败: ${e.message}")
            }
        }

        return result ?: createJsonResponse("静默打印完成", logs)
    }

    /**
     * 执行静默打印的核心方法
     */
    private fun performSilentPrint(
        connection: UsbDeviceConnection,
        outEndpoint: UsbEndpoint,
        inEndpoint: UsbEndpoint,
        fileBytes: ByteArray,
        uri: String
    ) {
        addLog("PRINT", "开始发送PDF数据到打印机")

        // 发送PDF原始数据到打印机
        val CHUNK_SIZE = 8192 // 8KB chunks
        var offset = 0

        while (offset < fileBytes.size) {
            val endIdx = minOf(offset + CHUNK_SIZE, fileBytes.size)
            val chunk = fileBytes.copyOfRange(offset, endIdx)
            
            val result = connection.bulkTransfer(outEndpoint, chunk, chunk.size, 5000)
            if (result < 0) {
                addLog("ERROR", "数据传输失败，已发送 $offset 字节")
                throw Exception("USB传输失败")
            }
            
            offset += chunk.size
            
            // 记录进度
            if (offset % (CHUNK_SIZE * 10) == 0) { // 每80KB记录一次
                val progress = (offset * 100) / fileBytes.size
                addLog("PROGRESS", "打印进度: $progress% ($offset / ${fileBytes.size} 字节)")
            }
        }

        addLog("PRINT", "PDF数据发送完成，总计: ${fileBytes.size} 字节")
        delPDFFile(uri)
        
        // 等待打印机处理
        Thread.sleep(1000)
    }

      /**
       * 删除 文件
       * @param url  文件的本地地址
       * @return 下载任务的 ID
       */
      private fun delPDFFile(url: String): String {
        try {
            val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadPath, url)
            if (!file.exists()) {
                return createJsonResponse("文件不存在：${file.absolutePath}", logs)
            } else {
                file.delete()
            }
            return createJsonResponse("开始删除文件", emptyList())
        } catch (e: Exception) {
            addLog("DOWNLOAD", "删除文件失败: ${e.message}")
            return createJsonResponse("删除文件失败: ${e.message}", emptyList())
        }
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

    /**
     * 使用Android系统打印框架进行打印（带预览）
     * @param device_path USB设备路径（在系统打印中不直接使用，由系统处理）
     * @return 返回JSON格式的打印结果和日志
     */
    @JvmName("printWithPreview")
    fun printWithPreview(device_path: String): String {
        logs.clear()
        
        try {
            // 检查权限
            if (!checkStoragePermissions()) {
                return createJsonResponse("需要存储权限，请授权后重试", logs)
            }

            // 检查PDF文件
            val file = File(Environment.getExternalStorageDirectory(), "test.pdf")
            if (!file.exists()) {
                return createJsonResponse("文件不存在：${file.absolutePath}", logs)
            }

            addLog("FILE", "找到PDF文件，大小: ${file.length()} 字节")

            // 获取系统打印管理器
            val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            
            // 创建PDF打印适配器
            val printAdapter = createPdfPrintAdapter(file)
            
            // 设置打印属性
            val printAttributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf_print", "PDF打印", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .setDuplexMode(PrintAttributes.DUPLEX_MODE_NONE)
                .build()

            // 启动打印作业
            val jobName = "PDF打印_${System.currentTimeMillis()}"
            val printJob = printManager.print(jobName, printAdapter, printAttributes)
            
            addLog("SUCCESS", "打印作业已提交，作业ID: ${printJob.id}")
            
            // 监控打印作业状态
            return monitorPrintJobStatus(printJob)

        } catch (e: Exception) {
            addLog("ERROR", "打印过程出错: ${e.message}")
            return createJsonResponse("打印失败: ${e.message}", logs)
        }
    }

    /**
     * 创建PDF打印适配器
     */
    private fun createPdfPrintAdapter(file: File): PrintDocumentAdapter {
        return object : PrintDocumentAdapter() {
            private var totalPages = 1
            
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                addLog("LAYOUT", "开始布局打印文档")
                
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    addLog("LAYOUT", "布局被取消")
                    return
                }
                
                try {
                    // 估算页数（这里简化处理，实际应该解析PDF获取页数）
                    totalPages = estimatePdfPages(file)
                    
                    val info = PrintDocumentInfo.Builder("${file.name}")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(totalPages)
                        .build()
                    
                    // 检查属性是否发生变化
                    val changed = oldAttributes == null || !oldAttributes.equals(newAttributes)
                    
                    callback?.onLayoutFinished(info, changed)
                    addLog("LAYOUT", "布局完成，页数: $totalPages")
                    
                } catch (e: Exception) {
                    addLog("ERROR", "布局失败: ${e.message}")
                    callback?.onLayoutFailed(e.message)
                }
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                addLog("WRITE", "开始写入打印数据")
                
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onWriteCancelled()
                    addLog("WRITE", "写入被取消")
                    return
                }

                try {
                    // 直接复制PDF文件内容到打印输出
                    val inputStream = FileInputStream(file)
                    val outputStream = FileOutputStream(destination?.fileDescriptor)
                    
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (cancellationSignal?.isCanceled == true) {
                            inputStream.close()
                            outputStream.close()
                            callback?.onWriteCancelled()
                            addLog("WRITE", "写入过程中被取消")
                            return
                        }
                        
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        
                        // 记录进度
                        if (totalBytes % (1024 * 100) == 0) { // 每100KB记录一次
                            addLog("PROGRESS", "已写入: ${totalBytes / 1024} KB")
                        }
                    }
                    
                    inputStream.close()
                    outputStream.close()
                    
                    addLog("WRITE", "写入完成，总计: ${totalBytes / 1024} KB")
                    
                    // 返回实际打印的页面范围
                    val printedPages = pages ?: arrayOf(PageRange.ALL_PAGES)
                    callback?.onWriteFinished(printedPages)
                    
                } catch (e: Exception) {
                    addLog("ERROR", "写入失败: ${e.message}")
                    callback?.onWriteFailed(e.message)
                }
            }

            override fun onStart() {
                addLog("PRINT", "打印作业开始")
                super.onStart()
            }

            override fun onFinish() {
                addLog("PRINT", "打印作业结束")
                super.onFinish()
            }
        }
    }

    /**
     * 估算PDF页数（简化版本）
     */
    private fun estimatePdfPages(file: File): Int {
        try {
            val bytes = file.readBytes()
            val content = String(bytes, StandardCharsets.ISO_8859_1)
            
            // 简单的页数估算，查找"/Count"标记
            val countPattern = Regex("/Count\\s+(\\d+)")
            val match = countPattern.find(content)
            
            return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
        } catch (e: Exception) {
            addLog("WARN", "无法估算页数，默认为1页: ${e.message}")
            return 1
        }
    }

    /**
     * 监控打印作业状态
     */
    private fun monitorPrintJobStatus(printJob: PrintJob): String {
        var waitCount = 0
        val maxWait = 60 // 最多等待60秒
        
        while (waitCount < maxWait) {
            try {
                Thread.sleep(1000)
                val jobInfo = printJob.info
                val stateString = getPrintJobStateString(jobInfo.state)
                
                addLog("STATUS", "打印作业状态[$waitCount]: $stateString")
                
                when (jobInfo.state) {
                    PrintJobInfo.STATE_COMPLETED -> {
                        addLog("COMPLETE", "打印作业完成")
                        return createJsonResponse("打印完成", logs)
                    }
                    PrintJobInfo.STATE_FAILED -> {
                        addLog("ERROR", "打印作业失败")
                        return createJsonResponse("打印失败", logs)
                    }
                    PrintJobInfo.STATE_CANCELED -> {
                        addLog("ERROR", "打印作业被取消")
                        return createJsonResponse("打印被取消", logs)
                    }
                    PrintJobInfo.STATE_BLOCKED -> {
                        addLog("WARN", "打印作业被阻塞，可能需要用户干预")
                        // 继续等待
                    }
                    PrintJobInfo.STATE_STARTED -> {
                        addLog("INFO", "打印正在进行中...")
                    }
                    PrintJobInfo.STATE_QUEUED -> {
                        addLog("INFO", "打印作业在队列中等待")
                    }
                }
                
                waitCount++
                
            } catch (e: Exception) {
                addLog("ERROR", "监控打印状态时出错: ${e.message}")
                break
            }
        }
        
        if (waitCount >= maxWait) {
            addLog("TIMEOUT", "打印作业监控超时")
            return createJsonResponse("打印超时，请检查打印机状态", logs)
        }
        
        return createJsonResponse("打印状态监控结束", logs)
    }

    /**
     * 获取打印作业状态字符串
     */
    private fun getPrintJobStateString(state: Int): String {
        return when (state) {
            PrintJobInfo.STATE_CREATED -> "已创建"
            PrintJobInfo.STATE_QUEUED -> "队列中"
            PrintJobInfo.STATE_STARTED -> "开始打印"
            PrintJobInfo.STATE_BLOCKED -> "被阻塞"
            PrintJobInfo.STATE_COMPLETED -> "完成"
            PrintJobInfo.STATE_FAILED -> "失败"
            PrintJobInfo.STATE_CANCELED -> "已取消"
            else -> "未知状态($state)"
        }
    }

    /**
     * 获取系统中可用的打印服务（简化版本）
     * @return 返回JSON格式的打印服务列表
     */
    @JvmName("getAvailablePrintServices")
    fun getAvailablePrintServices(): String {
        try {
            addLog("PRINT_SERVICES", "查询可用的打印服务")
            
            val servicesArray = JSONArray()
            
            // 返回默认打印机信息
            val defaultPrinter = JSONObject().apply {
                put("id", "default")
                put("name", "默认打印机")
                put("state", "ready")
                put("enabled", true)
            }
            servicesArray.put(defaultPrinter)
            
            // 如果有USB打印机，也添加到列表
            for (device in deviceList.values) {
                if (device.manufacturerName?.startsWith("HP") == true) {
                    val usbPrinter = JSONObject().apply {
                        put("id", device.deviceName)
                        put("name", "${device.manufacturerName} ${device.productName}")
                        put("state", "connected")
                        put("enabled", true)
                        put("type", "usb")
                    }
                    servicesArray.put(usbPrinter)
                }
            }
            
            val result = JSONObject().apply {
                put("success", true)
                put("services", servicesArray)
                put("count", servicesArray.length())
                put("message", "成功获取打印机列表")
            }
            
            return result.toString()
            
        } catch (e: Exception) {
            addLog("ERROR", "获取打印机失败: ${e.message}")
            val errorResult = JSONObject().apply {
                put("success", false)
                put("services", JSONArray())
                put("count", 0)
                put("error", e.message)
            }
            return errorResult.toString()
        }
    }

    
    /**
     * 下载 PDF 文件
     * @param url PDF 文件的下载链接
     * @return 下载任务的 ID
     */
    @JvmName("downloadPdf")
    fun downloadPdf(url: String): String {
        try {
            val timeStamp = System.currentTimeMillis()
            val fileName = "PDF_${timeStamp}.pdf"
             val request = DownloadManager.Request(Uri.parse(url)) 
                 .setTitle("${fileName} 下载") 
                 .setDescription("正在下载 PDF 文件") 
                 .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED) 
                 .setDestinationInExternalPublicDir( 
                     Environment.DIRECTORY_DOWNLOADS, 
                     fileName 
                 ) 
                 .setAllowedOverMetered(true) 
                 .setAllowedOverRoaming(true) 
 
             downloadManager.enqueue(request) 
             return createJsonResponse("开始下载 PDF 文件", listOf(fileName))
        } catch (e: Exception) {
            addLog("DOWNLOAD", "下载 PDF 文件失败: ${e.message}")
            return createJsonResponse("下载 PDF 文件失败: ${e.message}", emptyList())
        }
    }

    // private fun handleDownloadComplete() {
    //     val query = DownloadManager.Query().setFilterById(downloadId)
    //     val cursor: Cursor = downloadManager.query(query)
    //     if (cursor.moveToFirst()) {
    //         val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
    //         if (DownloadManager.STATUS_SUCCESSFUL == cursor.getInt(columnIndex)) {
    //             val uriString = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
    //             val pdfFile = File(Uri.parse(uriString).path!!)
    //             addLog("DOWNLOAD", "PDF 文件下载成功，路径: ${pdfFile.absolutePath}")
    //         } else {
    //             addLog("DOWNLOAD", "PDF 文件下载失败")
    //         }
    //     }
    //     cursor.close()
    // }
}