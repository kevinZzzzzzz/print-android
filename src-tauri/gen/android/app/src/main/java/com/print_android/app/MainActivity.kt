package com.print_android.app

import android.os.Bundle
import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent
import android.provider.MediaStore
import android.app.Activity
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import android.content.ContentResolver
import java.io.InputStream
import androidx.core.content.FileProvider
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import android.os.Environment
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest

class MainActivity : TauriActivity() {
    private var currentPhotoPath: String? = null
    private var isPhotoInProgress: Boolean = false
    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    
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
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            Log.d("UsbDevices", "getConnectedUsbDevices called")
            val deviceList = usbManager.deviceList
            
            val devicesArray = JSONArray()
            // 打印devicesArray
            Log.d("UsbDevices", "devicesArray: $devicesArray")
            
            for ((_, device) in deviceList) {
                val deviceInfo = JSONObject().apply {
                    put("device_name", device.deviceName ?: "Unknown Device")
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
            
            Log.d("UsbDevices", "Found ${devicesArray.length()} USB devices")
            return devicesArray.toString()
        } catch (e: Exception) {
            Log.e("UsbDevices", "Error getting USB devices: ${e.message}")
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
} 