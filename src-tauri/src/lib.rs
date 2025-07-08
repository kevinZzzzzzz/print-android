// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize)]
pub struct PrinterInfo {
    name: String,
    is_default: bool,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PhotoInfo {
    path: String,
    uri: String,
    status: Option<String>,
    message: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct UsbDeviceInfo {
    device_name: String,
    vendor_id: i32,
    product_id: i32,
    device_class: i32,
    device_protocol: i32,
    manufacturer_name: Option<String>,
    product_name: Option<String>,
    serial_number: Option<String>,
}

#[tauri::command]
fn greet(name: &str) -> String {
    format!("你好, {}! 欢迎使用打印应用!", name)
}

// #[tauri::command]
// async fn print_document(_content: String, _silent: bool) -> Result<String, String> {
//     #[cfg(target_os = "android")]
//     {
//         // Android 端的打印功能将通过 Android 原生代码处理
//         // 这里只是 Rust 端的接口，实际实现在 Android 端
//         Ok("打印请求已发送".to_string())
//     }

//     #[cfg(not(target_os = "android"))]
//     {
//         // 桌面端打印功能，可以通过系统API实现
//         Err("桌面端暂不支持打印功能".to_string())
//     }
// }

#[tauri::command]
async fn get_connected_printers() -> Result<Vec<PrinterInfo>, String> {
    #[cfg(target_os = "android")]
    {
        // Android 端的获取打印机功能将通过 Android 原生代码处理
        // 这里返回一个示例结果
        Ok(vec![PrinterInfo {
            name: "默认打印机".to_string(),
            is_default: true,
        }])
    }

    #[cfg(not(target_os = "android"))]
    {
        Err("桌面端暂不支持获取打印机功能".to_string())
    }
}

#[tauri::command]
async fn get_usb_devices() -> Result<Vec<UsbDeviceInfo>, String> {
    #[cfg(target_os = "android")]
    {
        println!("get_usb_devices called");

        // 通过JNI调用Android的getConnectedUsbDevices方法
        call_android_usb_method().await
    }

    #[cfg(not(target_os = "android"))]
    {
        Err("桌面端暂不支持获取 USB 设备功能".to_string())
    }
}

#[cfg(target_os = "android")]
async fn call_android_usb_method() -> Result<Vec<UsbDeviceInfo>, String> {
    use jni::objects::JObject;
    
    // 获取当前的JNI环境和Activity
    let ctx = ndk_context::android_context();
    println!("ctx: {:?}", ctx);
    let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }
        .map_err(|e| format!("Failed to get JavaVM: {}", e))?;
    
    let mut env = vm.attach_current_thread()
        .map_err(|e| format!("Failed to attach thread: {}", e))?;
    
    let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
    
    // 调用Android的getConnectedUsbDevices方法
    let jstring_obj = env.call_method(
        &activity,
        "getConnectedUsbDevices",
        "()Ljava/lang/String;",
        &[]
    ).map_err(|e| format!("Failed to call getConnectedUsbDevices: {}", e))?
    .l().map_err(|e| format!("Failed to convert to object: {}", e))?;
    
    let json_string = env.get_string(&jstring_obj.into())
        .map_err(|e| format!("Failed to get string: {}", e))?
        .to_string_lossy()
        .to_string();
    println!("json_string: {:?}", json_string);
    
    parse_usb_devices_json(&json_string)
}

#[cfg(target_os = "android")]
fn parse_usb_devices_json(json_str: &str) -> Result<Vec<UsbDeviceInfo>, String> {
    use serde_json::Value;
    
    let devices: Value = serde_json::from_str(json_str)
        .map_err(|e| format!("JSON解析失败: {}", e))?;
    
    let mut usb_devices = Vec::new();
    
    if let Value::Array(device_array) = devices {
        for device in device_array {
            if let Value::Object(device_obj) = device {
                let usb_device = UsbDeviceInfo {
                    device_name: device_obj.get("device_name")
                        .and_then(|v| v.as_str())
                        .unwrap_or("Unknown Device")
                        .to_string(),
                    vendor_id: device_obj.get("vendor_id")
                        .and_then(|v| v.as_i64())
                        .unwrap_or(0) as i32,
                    product_id: device_obj.get("product_id")
                        .and_then(|v| v.as_i64())
                        .unwrap_or(0) as i32,
                    device_class: device_obj.get("device_class")
                        .and_then(|v| v.as_i64())
                        .unwrap_or(0) as i32,
                    device_protocol: device_obj.get("device_protocol")
                        .and_then(|v| v.as_i64())
                        .unwrap_or(0) as i32,
                    manufacturer_name: device_obj.get("manufacturer_name")
                        .and_then(|v| v.as_str())
                        .filter(|s| !s.is_empty())
                        .map(|s| s.to_string()),
                    product_name: device_obj.get("product_name")
                        .and_then(|v| v.as_str())
                        .filter(|s| !s.is_empty())
                        .map(|s| s.to_string()),
                    serial_number: device_obj.get("serial_number")
                        .and_then(|v| v.as_str())
                        .filter(|s| !s.is_empty())
                        .map(|s| s.to_string()),
                };
                usb_devices.push(usb_device);
            }
        }
    }
    
    Ok(usb_devices)
}

#[tauri::command]
async fn take_photo() -> Result<PhotoInfo, String> {
    #[cfg(target_os = "android")]
    {
        call_android_photo_method().await
    }

    #[cfg(not(target_os = "android"))]
    {
        Err("桌面端暂不支持拍照功能".to_string())
    }
}

#[tauri::command]
async fn get_photo_result() -> Result<PhotoInfo, String> {
    #[cfg(target_os = "android")]
    {
        call_android_get_photo_result().await
    }

    #[cfg(not(target_os = "android"))]
    {
        Err("桌面端暂不支持拍照功能".to_string())
    }
}

#[cfg(target_os = "android")]
async fn call_android_photo_method() -> Result<PhotoInfo, String> {
    use jni::objects::JObject;
    
    // 获取当前的JNI环境和Activity
    let ctx = ndk_context::android_context();
    let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }
        .map_err(|e| format!("Failed to get JavaVM: {}", e))?;
    
    let mut env = vm.attach_current_thread()
        .map_err(|e| format!("Failed to attach thread: {}", e))?;
    
    let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
    
    // 调用Android的takePhotoBase64方法
    let jstring_obj = env.call_method(
        &activity,
        "takePhotoBase64",
        "()Ljava/lang/String;",
        &[]
    ).map_err(|e| format!("Failed to call takePhotoBase64: {}", e))?
    .l().map_err(|e| format!("Failed to convert to object: {}", e))?;
    
    let json_string = env.get_string(&jstring_obj.into())
        .map_err(|e| format!("Failed to get string: {}", e))?
        .to_string_lossy()
        .to_string();
    
    parse_photo_json(&json_string)
}

#[cfg(target_os = "android")]
async fn call_android_get_photo_result() -> Result<PhotoInfo, String> {
    use jni::objects::JObject;
    
    // 获取当前的JNI环境和Activity
    let ctx = ndk_context::android_context();
    let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }
        .map_err(|e| format!("Failed to get JavaVM: {}", e))?;
    
    let mut env = vm.attach_current_thread()
        .map_err(|e| format!("Failed to attach thread: {}", e))?;
    
    let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
    
    // 调用Android的getPhotoResult方法
    let jstring_obj = env.call_method(
        &activity,
        "getPhotoResult",
        "()Ljava/lang/String;",
        &[]
    ).map_err(|e| format!("Failed to call getPhotoResult: {}", e))?
    .l().map_err(|e| format!("Failed to convert to object: {}", e))?;
    
    let json_string = env.get_string(&jstring_obj.into())
        .map_err(|e| format!("Failed to get string: {}", e))?
        .to_string_lossy()
        .to_string();
    
    parse_photo_json(&json_string)
}

#[cfg(target_os = "android")]
fn parse_photo_json(json_str: &str) -> Result<PhotoInfo, String> {
    use serde_json::Value;
    
    let photo_data: Value = serde_json::from_str(json_str)
        .map_err(|e| format!("JSON解析失败: {}", e))?;
    
    if let Value::Object(photo_obj) = photo_data {
        if let Some(error) = photo_obj.get("error") {
            return Err(format!("拍照失败: {}", error.as_str().unwrap_or("未知错误")));
        }
        
        let photo_info = PhotoInfo {
            path: photo_obj.get("path")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            uri: photo_obj.get("uri")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            status: photo_obj.get("status")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string()),
            message: photo_obj.get("message")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string()),
        };
        
        Ok(photo_info)
    } else {
        Err("无效的照片数据格式".to_string())
    }
}

// 帮我写一个调用打印的方法
#[tauri::command]
async fn print_document(device_path: String) -> Result<String, String> {
    println!("调用打印方法，设备路径: {}", device_path);

    #[cfg(target_os = "android")]
    {
        call_android_print_method(device_path).await
    }

    #[cfg(not(target_os = "android"))]
    {
        Err("桌面端暂不支持打印功能".to_string())
    }
}

#[cfg(target_os = "android")]
async fn call_android_print_method(device_path: String) -> Result<String, String> {
    use jni::objects::{JObject, JString};
    
    // 获取当前的JNI环境和Activity
    let ctx = ndk_context::android_context();
    let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }
        .map_err(|e| format!("Failed to get JavaVM: {}", e))?;
    
    let mut env = vm.attach_current_thread()
        .map_err(|e| format!("Failed to attach thread: {}", e))?;
    
    let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
    println!("开始调用打印方法");
    
    // 将 Rust String 转换为 Java String
    let j_device_path = env.new_string(&device_path)
        .map_err(|e| format!("Failed to create Java string: {}", e))?;
    
    // 调用Android的print方法并获取返回值
    let result = env.call_method(
        &activity,
        "print",
        "(Ljava/lang/String;)Ljava/lang/String;",
        &[(&j_device_path).into()]
    ).map_err(|e| format!("Failed to call print method: {}", e))?;

    // 获取返回的字符串
    let jstring_result = result.l()
        .map_err(|e| format!("Failed to get result object: {}", e))?;
    
    let response = if jstring_result.is_null() {
        "打印完成".to_string()
    } else {
        env.get_string(&jstring_result.into())
            .map_err(|e| format!("Failed to get response string: {}", e))?
            .to_string_lossy()
            .to_string()
    };

    Ok(response)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            greet,
            // print_document,
            get_connected_printers,
            get_usb_devices,
            take_photo,
            get_photo_result,
            print_document
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
