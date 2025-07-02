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
}



#[tauri::command]
fn greet(name: &str) -> String {
    format!("你好, {}! 欢迎使用打印应用!", name)
}

#[tauri::command]
async fn print_document(
    _content: String,
    _silent: bool,
) -> Result<String, String> {
    #[cfg(target_os = "android")]
    {
        // Android 端的打印功能将通过 Android 原生代码处理
        // 这里只是 Rust 端的接口，实际实现在 Android 端
        Ok("打印请求已发送".to_string())
    }

    #[cfg(not(target_os = "android"))]
    {
        // 桌面端打印功能，可以通过系统API实现
        Err("桌面端暂不支持打印功能".to_string())
    }
}

#[tauri::command]
async fn get_connected_printers() -> Result<Vec<PrinterInfo>, String> {
    #[cfg(target_os = "android")]
    {
        // Android 端的获取打印机功能将通过 Android 原生代码处理
        // 这里返回一个示例结果
        Ok(vec![
            PrinterInfo {
                name: "默认打印机".to_string(),
                is_default: true,
            }
        ])
    }

    #[cfg(not(target_os = "android"))]
    {
        Err("桌面端暂不支持获取打印机功能".to_string())
    }
}

#[tauri::command]
async fn take_photo() -> Result<PhotoInfo, String> {
    #[cfg(target_os = "android")]
    {
        // Android 端的拍照功能将通过 Android 原生代码处理
        // 这里返回一个示例结果
        Ok(PhotoInfo {
            path: "/sdcard/DCIM/Camera/photo.jpg".to_string(),
            uri: "content://media/external/images/media/1".to_string(),
        })
    }

    #[cfg(not(target_os = "android"))]
    {
        Err("桌面端暂不支持拍照功能".to_string())
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            greet,
            print_document,
            get_connected_printers,
            take_photo
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
