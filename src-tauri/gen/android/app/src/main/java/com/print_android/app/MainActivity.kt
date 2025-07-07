package com.print_android.app

import android.app.PendingIntent
import android.os.Bundle
import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
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

class MainActivity : TauriActivity() {
    private var currentPhotoPath: String? = null
    private var isPhotoInProgress: Boolean = false
    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    private val USB_PERMISSION_REQUEST_CODE = 101

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 处理拍照成功
            handleCameraResult()
        } else {
            // 处理拍照失败或取消
            Log.d("TakePhoto", "Camera cancelled or failed")
            currentPhotoPath = null
            isPhotoInProgress = false
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    // 获取连接的USB设备列表
    @JvmName("getConnectedUsbDevices")
    fun getConnectedUsbDevices(): String {
      // 打印输出日志到终端
      Log.println(Log.INFO, "getConnectedUsbDevices", "getConnectedUsbDevices called");
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            Log.println(Log.INFO,"UsbDevices", "getConnectedUsbDevices called")
            val deviceList = usbManager.deviceList
            
            val devicesArray = JSONArray()
            // 打印devicesArray
            Log.println(Log.INFO,"UsbDevices", "devicesArray: $devicesArray")
            
            for (device in deviceList.values) {
              Log.println(Log.INFO,"USB", """
                  Device Name: ${device.deviceName}
                  Vendor ID: ${device.vendorId}   // 厂商ID（如佳博打印机为 1137）
                  Product ID: ${device.productId}  // 产品ID
                  Interface Count: ${device.interfaceCount}
              """)
                // 判断manufacturer_name 是 HP 开头的
                if (device.manufacturerName?.startsWith("HP") == true) {
                  val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent("com.usb.printer.USB_PERMISSION"), 
                  PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
            Log.println(Log.INFO,"UsbDevices", "Found ${devicesArray.length()} USB devices")
            return devicesArray.toString()
        } catch (e: Exception) {
            Log.println(Log.INFO,"UsbDevices", "Error getting USB devices: ${e.message}")
            return "[]"
        }
    }

    // 获取照片的Base64编码（真实拍照功能）
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
    
    // 获取拍照结果
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

    @JvmName("print")
    fun print(): String {
        val logs = mutableListOf<String>()
        fun addLog(message: String) {
            Log.d(TAG, message)
            logs.add(message)
        }

        var connection: UsbDeviceConnection? = null
        var usbInterface: UsbInterface? = null

        try {
            addLog("开始打印1")
            // 检查存储权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    // 请求所有文件访问权限
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        addCategory("android.intent.category.DEFAULT")
                        data = Uri.parse("package:${applicationContext.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    return createJsonResponse("需要文件访问权限，请在设置中授权后重试", logs)
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        1001
                    )
                    return createJsonResponse("需要存储权限，请授权后重试", logs)
                }
            }

            // 获取USB管理器
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            
            // 获取目标打印机设备
            val devicePath = "/dev/bus/usb/009/003"
            var targetDevice: UsbDevice? = null
            
            // 查找目标打印机
            for (device in usbManager.deviceList.values) {
                addLog("发现USB设备: ${device.deviceName}, VendorId: ${device.vendorId}, ProductId: ${device.productId}")
                if (device.deviceName == devicePath) {
                    targetDevice = device
                    break
                }
            }
            
            if (targetDevice == null) {
                return createJsonResponse("未找到指定的打印机设备：$devicePath", logs)
            }

            addLog("找到打印机设备: ${targetDevice.deviceName}")
            addLog("制造商: ${targetDevice.manufacturerName}, 产品: ${targetDevice.productName}")
            
            // 检查是否有USB设备权限
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

            try {
                // 确保之前的连接已经关闭
                connection?.close()
                usbInterface?.let { intf ->
                    connection?.releaseInterface(intf)
                }

                // 打开USB连接
                connection = usbManager.openDevice(targetDevice)
                if (connection == null) {
                    return createJsonResponse("无法连接到打印机设备", logs)
                }

                // 读取PDF文件
                val file = File(Environment.getExternalStorageDirectory(), "test.pdf")
                if (!file.exists()) {
                    return createJsonResponse("文件不存在：${file.absolutePath}", logs)
                }

                val inputStream = FileInputStream(file)
                val fileBytes = inputStream.readBytes()
                inputStream.close()

                addLog("成功读取文件，大小: ${fileBytes.size} 字节")

                // 获取USB接口
                usbInterface = targetDevice.getInterface(0)
                addLog("USB接口信息: interfaceClass=${usbInterface.interfaceClass}, " +
                      "interfaceSubclass=${usbInterface.interfaceSubclass}, " +
                      "interfaceProtocol=${usbInterface.interfaceProtocol}")

                // 获取所有端点信息
                for (i in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(i)
                    addLog("端点 $i: type=${endpoint.type}, direction=${endpoint.direction}, " +
                          "address=${endpoint.address}, attributes=${endpoint.attributes}")
                }

                // 查找输出端点（用于发送数据到打印机）
                var outEndpoint = usbInterface.getEndpoint(0)
                for (i in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(i)
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        outEndpoint = endpoint
                        break
                    }
                }
                
                // 声明USB接口
                if (!connection.claimInterface(usbInterface, true)) {
                    connection.close()
                    return createJsonResponse("无法声明USB接口", logs)
                }

                addLog("准备发送数据到打印机...")
                
                // 重置打印机
                val resetCommand = byteArrayOf(0x1B, 0x40)  // ESC @ 命令，重置打印机
                val resetResult = connection.bulkTransfer(outEndpoint, resetCommand, resetCommand.size, 1000)
                if (resetResult < 0) {
                    addLog("打印机重置失败")
                }

                // 等待打印机重置
                Thread.sleep(100)

                // 初始化打印机
                val initCommand = byteArrayOf(0x1B, 0x40)  // ESC @ 命令，初始化打印机
                val initResult = connection.bulkTransfer(outEndpoint, initCommand, initCommand.size, 1000)
                if (initResult < 0) {
                    connection.releaseInterface(usbInterface)
                    connection.close()
                    return createJsonResponse("打印机初始化失败", logs)
                }

                // 等待打印机就绪
                Thread.sleep(100)

                // 检查打印机状态
                val statusCommand = byteArrayOf(0x1D, 0x72, 0x01)  // GS r n 命令，获取打印机状态
                // val statusBuffer = ByteArray(1)
                val statusResult = connection.bulkTransfer(outEndpoint, statusCommand, statusCommand.size, 1000)
                if (statusResult < 0) {
                    addLog("获取打印机状态失败，尝试继续打印")
                }

                // 分块发送数据
                val CHUNK_SIZE = 1024
                var offset = 0
                while (offset < fileBytes.size) {
                    val chunk = fileBytes.slice(offset..kotlin.math.min(offset + CHUNK_SIZE - 1, fileBytes.size - 1)).toByteArray()
                    val result = connection.bulkTransfer(outEndpoint, chunk, chunk.size, 5000)
                    
                    if (result < 0) {
                        connection.releaseInterface(usbInterface)
                        connection.close()
                        return createJsonResponse("数据传输失败，已发送 $offset 字节", logs)
                    }
                    
                    offset += chunk.size
                    addLog("已发送: $offset / ${fileBytes.size} 字节")

                    // 每次传输后短暂等待，避免打印机缓冲区溢出
                    Thread.sleep(10)
                }
                
                // 发送换页命令
                val formFeedCommand = byteArrayOf(0x0C)  // FF 命令，换页
                connection.bulkTransfer(outEndpoint, formFeedCommand, formFeedCommand.size, 1000)
                
                // 等待打印完成
                Thread.sleep(500)
                
                // 释放资源
                connection.releaseInterface(usbInterface)
                connection.close()
                connection = null
                usbInterface = null
                
                addLog("打印数据发送完成")
                return createJsonResponse("打印数据已成功发送到打印机", logs)
            } catch (e: Exception) {
                addLog("打印过程出错: ${e.message}")
                // 确保资源被释放
                try {
                    usbInterface?.let { intf ->
                        connection?.releaseInterface(intf)
                    }
                    connection?.close()
                } catch (closeError: Exception) {
                    addLog("关闭连接时出错: ${closeError.message}")
                }
                return createJsonResponse("打印失败: ${e.message}", logs)
            } finally {
                // 最后确保资源被释放
                try {
                    usbInterface?.let { intf ->
                        connection?.releaseInterface(intf)
                    }
                    connection?.close()
                } catch (closeError: Exception) {
                    addLog("关闭连接时出错: ${closeError.message}")
                }
            }
        } catch (e: Exception) {
            addLog("打印初始化失败: ${e.message}")
            return createJsonResponse("打印初始化失败: ${e.message}", logs)
        }
    }
    
    // 创建图片文件
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
    
    // 处理拍照结果
    private fun handleCameraResult() {
        Log.d("TakePhoto", "Camera result received, photo path: $currentPhotoPath")
        isPhotoInProgress = false // 标记拍照完成
    }
    
    // 调整图片大小以减小文件size
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
    
    // 创建错误结果
    private fun createErrorResult(errorMessage: String): String {
        val errorResult = JSONObject().apply {
            put("path", "")
            put("uri", "")
            put("error", errorMessage)
        }
        return errorResult.toString()
    }
    // 处理权限请求结果
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

    private fun createJsonResponse(message: String, logs: List<String>): String {
        return JSONObject().apply {
            put("message", message)
            put("logs", JSONArray(logs))
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
} 