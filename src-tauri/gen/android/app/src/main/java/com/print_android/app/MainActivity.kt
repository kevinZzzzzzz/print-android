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

class MainActivity : TauriActivity() {
    private var currentPhotoPath: String? = null
    private var isPhotoInProgress: Boolean = false
    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    private val USB_PERMISSION_REQUEST_CODE = 101

    val logs = mutableListOf<String>()
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

    // 获取连接的USB设备列表
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
                // addLog("USB", """
                //     Device Name: ${device.deviceName}
                //     Vendor ID: ${device.vendorId}   // 厂商ID（如佳博打印机为 1137）
                //     Product ID: ${device.productId}  // 产品ID
                //     Interface Count: ${device.interfaceCount}
                // """)
                
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
    fun print(device_path: String): String {
      logs.clear()
        // 检查存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
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
      // 读取PDF文件
      val file = File(Environment.getExternalStorageDirectory(), "test.pdf")
      if (!file.exists()) {
          return createJsonResponse("文件不存在：${file.absolutePath}", logs)
      }
      val inputStream = FileInputStream(file)
      val fileBytes = inputStream.readBytes()
      inputStream.close()

      addLog("FILE", "成功读取文件，大小: ${fileBytes.size} 字节")

      // 获取目标打印机设备
      devicePath = device_path
      
      // 查找目标打印机
      targetDevice = deviceList.values.find { it.deviceName == devicePath }
      
      val currentDevice = targetDevice
      if (currentDevice == null) {
          return createJsonResponse("未找到指定的打印机设备：$devicePath", logs)
      }

      addLog("DEVICE", "找到打印机设备: ${currentDevice.deviceName}")
      addLog("DEVICE", "制造商: ${currentDevice.manufacturerName}, 产品: ${currentDevice.productName}")
      
      // 检查是否有USB设备权限
      if (!usbManager.hasPermission(currentDevice)) {
          val permissionIntent = PendingIntent.getBroadcast(
              this, 
              0, 
              Intent(ACTION_USB_PERMISSION),
              PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
          )
          usbManager.requestPermission(currentDevice, permissionIntent)
          return createJsonResponse("需要USB设备权限，请授权后重试", logs)
      }

      addLog("PRINT", "获取打印服务, ${printManager}, ${printManager.printJobs.size}")
      
      //  连接USB设备并查找端点 获取USB接口
      val currentInterface = currentDevice.getInterface(0)
      usbInterface = currentInterface
      
      addLog("USB", "USB接口信息: interfaceClass=${currentInterface.interfaceClass}, " + 
                    "interfaceSubclass=${currentInterface.interfaceSubclass}, " +  
                    "interfaceProtocol=${currentInterface.interfaceProtocol}")
      
      // 查找输出端点
      outEndpoint = (0 until currentInterface.endpointCount)
          .map { currentInterface.getEndpoint(it) }
          .find { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && 
                  it.direction == UsbConstants.USB_DIR_OUT }
      
      val currentEndpoint = outEndpoint
      if (currentEndpoint == null) {
          return createJsonResponse("未找到合适的输出端点", logs)
      }
      
      addLog("USB", "输出端点: $currentEndpoint ${UsbConstants.USB_DIR_OUT}")
      
      // 打开USB连接
      connection = usbManager.openDevice(currentDevice)
      val currentConnection = connection
      
      if (currentConnection == null) {
          return createJsonResponse("无法连接到打印机设备", logs)
      }
      
      currentConnection.claimInterface(currentInterface, true)

      try {
          addLog("PRINT", "开始打印")
          var start = System.currentTimeMillis();
          addLog("PRINT", "准备发送数据到打印机....")

          // 初始化打印机
          val initCommand = byteArrayOf(0x1B, 0x40, 0x1B, 0x3F, 0x0C)  // ESC @ 命令，初始化打印机
          val initResult = currentConnection.bulkTransfer(currentEndpoint, initCommand, initCommand.size, 1000)
          if (initResult < 0) {
              currentConnection.releaseInterface(currentInterface)
              currentConnection.close()
              return createJsonResponse("打印机初始化失败", logs)
          }
          Thread.sleep(1000) // 等待初始化打印机完成
          // 关键步骤1：发送惠普复位指令
          val resetCmd = "@PJL DEFAULT RESET=ALL\n".toByteArray(StandardCharsets.US_ASCII)
          val resetResult = currentConnection.bulkTransfer(currentEndpoint, resetCmd, resetCmd.size, 5000)
          if (resetResult < 0) {
              return createJsonResponse("发送复位指令失败", logs)
          }
          // 惠普专用作业结束指令
          val jobEndCmd22 = "@PJL EOJ\n".toByteArray(StandardCharsets.US_ASCII)
          currentConnection.bulkTransfer(currentEndpoint, jobEndCmd22, jobEndCmd22.size, 1000)
          Thread.sleep(1000) // 等待复位完成


          // 关键步骤2：分块传输数据
          val CHUNK_SIZE = currentEndpoint.maxPacketSize
          var offset = 0
          while (offset < fileBytes.size) {
              val endIdx = minOf(offset + CHUNK_SIZE, fileBytes.size)
              val chunk = fileBytes.copyOfRange(offset, endIdx)
              val result = currentConnection.bulkTransfer(currentEndpoint, chunk, chunk.size, 10000)
              addLog("PRINTING", "${result} ")
              
              if (result < 0) {
                  currentConnection.releaseInterface(currentInterface)
                  currentConnection.close()
                  return createJsonResponse("数据传输失败，已发送 $offset 字节", logs)
              }
              offset += chunk.size
              addLog("PRINT", "已发送: $offset / ${fileBytes.size} 字节")

              // 每次传输后短暂等待，避免打印机缓冲区溢出
              // Thread.sleep(100)
          }

          // 关键步骤5：清空系统打印队列
          clearSystemPrintJobs()
          
          // 关键步骤3：发送结束指令
          
          val resetCmd11 = "@PJL DEFAULT RESET=ALL\n".toByteArray(StandardCharsets.US_ASCII)
          currentConnection.bulkTransfer(currentEndpoint, resetCmd11, resetCmd11.size, 5000)
          // 惠普专用作业结束指令
          val jobEndCmd = "@PJL EOJ\n".toByteArray(StandardCharsets.US_ASCII)
          currentConnection.bulkTransfer(currentEndpoint, jobEndCmd, jobEndCmd.size, 1000)
          addLog("PRINTEND", "发送作业结束指令")

          
          val formFeedCommand = byteArrayOf(0x1B, 0x40, 0x1B, 0x3F, 0x0C)  // 重置配置
          val result1 = currentConnection.bulkTransfer(currentEndpoint, formFeedCommand, formFeedCommand.size, 1000)
          addLog("PRINTEND", "重置配置 ${result1}")
          // 关键步骤4：等待打印机处理（3秒）
          // Thread.sleep(3000)
          // Thread.sleep(1000)
          
          //查询打印机状态指令
          val queryCmd = "@PJL INFO STATUS\r\n".toByteArray(StandardCharsets.US_ASCII)
          val result3 = currentConnection.bulkTransfer(currentEndpoint, queryCmd, queryCmd.size, 1000)
          addLog("PRINTEND", "查询打印机状态指令 ${result3}")

          addLog("PRINTEND", "打印数据发送完成")
          var end = System.currentTimeMillis();
          addLog("TIME", "打印耗时: ${end - start} ms");
          return createJsonResponse("打印数据已成功发送到打印机", logs)
      } catch (e: Exception) {
          addLog("ERROR", "打印过程出错: ${e.message}")
          return createJsonResponse("打印失败: ${e.message}", logs)
      } finally {
        addLog("END", "打印结束")
      }
    }
     // 5. 清空系统打印队列
     private fun clearSystemPrintJobs() {
        addLog("CLEAR", "开始清理系统打印队列")
      val printManager = this.getSystemService(Context.PRINT_SERVICE) as PrintManager
        addLog("CLEAR", "获取打印服务, ${printManager}, ${printManager.printJobs.size}")
      printManager.printJobs.forEach { job ->
        job.cancel() // 强制取消任务[1,4](@ref)
          // if (job.info.state == PrintJobInfo.STATE_STARTED || 
          //     job.info.state == PrintJobInfo.STATE_QUEUED
          // ) {
          //     job.cancel() // 强制取消任务[1,4](@ref)
          // }
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