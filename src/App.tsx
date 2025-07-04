/** @ts-ignore */
import React, { useState, useEffect } from "react"
import { invoke } from "@tauri-apps/api/core"
import "./App.css"
import Vconsole from "vconsole"

new Vconsole()
// 定义打印机类型
interface Printer {
  name: string
  is_default: boolean
}

// 定义照片信息类型
interface PhotoInfo {
  path: string
  uri: string
  status?: string
  message?: string
}

// 定义USB设备信息类型
interface UsbDeviceInfo {
  device_name: string
  vendor_id: number
  product_id: number
  device_class: number
  device_protocol: number
  manufacturer_name?: string
  product_name?: string
  serial_number?: string
}

function App() {
  // 状态管理
  const [printers, setPrinters] = useState<Printer[]>([])
  const [selectedPrinter, setSelectedPrinter] = useState<string>("")
  const [printContent, setPrintContent] = useState<string>(
    "<h1>测试打印内容</h1><p>这是一个测试打印文档。</p>"
  )
  const [isSilentPrint, setIsSilentPrint] = useState<boolean>(false)
  const [photoUri, setPhotoUri] = useState<string | null>(null)
  const [isCapturing, setIsCapturing] = useState<boolean>(false)
  const [captureMessage, setCaptureMessage] = useState<string>("")
  const [usbDevices, setUsbDevices] = useState<UsbDeviceInfo[]>([])
  const [loading, setLoading] = useState<boolean>(false)
  const [message, setMessage] = useState<string>("")

  // 获取连接的打印机列表
  const fetchPrinters = async () => {
    try {
      console.log("正在获取打印机列表..........")
      setLoading(true)
      setMessage("正在获取打印机列表...")
      const result = await invoke<Printer[]>("get_usb_devices")
      setPrinters(result)
      console.log(result, result.length, "result-33-----------")
      if (result.length > 0) {
        // 设置默认打印机
        const defaultPrinter = result.find((p) => p.is_default) || result[0]
        setSelectedPrinter(defaultPrinter.name)
      }
      setMessage(`找到 ${result.length} 台打印机`)
    } catch (error) {
      console.error("获取打印机列表失败:", error)
      setMessage(`获取打印机列表失败: ${error}`)
    } finally {
      setLoading(false)
    }
  }
  const handlePrintWindow = async () => {
    console.log("点击打印", window)
    await invoke<string>("print_document")
  }
  // 打印文档
  const handlePrint = async () => {
    try {
      setLoading(true)
      setMessage("正在打印...")
      await invoke<string>("print_document")
      setMessage("打印成功")
    } catch (error) {
      console.error("打印失败:", error)
      setMessage(`打印失败: ${error}`)
    } finally {
      setLoading(false)
    }
  }

  // 获取USB设备列表
  const fetchUsbDevices = async () => {
    try {
      setLoading(true)
      setMessage("正在获取USB设备列表...")
      const result = await invoke<UsbDeviceInfo[]>("get_usb_devices")
      setUsbDevices(result)
      setMessage(`找到 ${result.length} 个USB设备`)
    } catch (error) {
      console.error("获取USB设备失败:", error)
      setMessage(`获取USB设备失败: ${error}`)
    } finally {
      setLoading(false)
    }
  }

  // 轮询获取拍照结果
  const pollPhotoResult = async () => {
    try {
      const result = await invoke<PhotoInfo>("get_photo_result")
      setCaptureMessage(result.message || "")

      if (result.status === "completed" && result.uri) {
        setPhotoUri(result.uri)
        setIsCapturing(false)
        setMessage("拍照完成！")
        return true // 停止轮询
      } else if (result.status === "in_progress") {
        return false // 继续轮询
      } else {
        // 处理错误或其他状态
        setIsCapturing(false)
        setMessage(`拍照失败: ${result.message || "未知错误"}`)
        return true // 停止轮询
      }
    } catch (error) {
      console.error("获取拍照结果失败:", error)
      setIsCapturing(false)
      setMessage(`获取拍照结果失败: ${error}`)
      return true // 停止轮询
    }
  }

  // 拍照
  const handleTakePhoto = async () => {
    try {
      setLoading(true)
      setMessage("正在启动相机...")
      setIsCapturing(true)
      setCaptureMessage("")
      setPhotoUri(null)

      const photo = await invoke<PhotoInfo>("take_photo")
      console.log(photo, "photo-----------------")

      if (photo.status === "camera_launched") {
        setMessage(photo.message || "相机已启动")
        setLoading(false)

        // 开始轮询获取结果
        const pollInterval = setInterval(async () => {
          const shouldStop = await pollPhotoResult()
          if (shouldStop) {
            clearInterval(pollInterval)
          }
        }, 1000) // 每秒检查一次

        // 设置超时，避免无限轮询
        setTimeout(() => {
          clearInterval(pollInterval)
          if (isCapturing) {
            setIsCapturing(false)
            setMessage("拍照超时，请重试")
          }
        }, 30000) // 30秒超时
      } else if (photo.uri) {
        // 直接获得了结果
        setPhotoUri(photo.uri)
        setMessage("拍照成功")
        setIsCapturing(false)
      } else {
        setMessage(photo.message || "拍照失败")
        setIsCapturing(false)
      }
    } catch (error) {
      console.error("拍照失败:", error)
      setMessage(`拍照失败: ${error}`)
      setIsCapturing(false)
    } finally {
      if (!isCapturing) {
        setLoading(false)
      }
    }
  }

  // 组件挂载时获取打印机列表
  useEffect(() => {
    fetchPrinters()
  }, [])

  return (
    <div className="container">
      <h1>打印和拍照应用</h1>

      {/* 打印机选择区域 */}
      <div className="card">
        <h2>打印机设置</h2>
        <button onClick={fetchPrinters} disabled={loading}>
          刷新打印机列表
        </button>
        <button onClick={handlePrintWindow}>点击打印</button>
        <div className="form-group">
          <label>选择打印机:</label>
          <select
            value={selectedPrinter}
            onChange={(e) => setSelectedPrinter(e.target.value)}
            disabled={printers.length === 0 || loading}
          >
            {printers.length === 0 && <option value="">无可用打印机</option>}
            {printers.map((printer: any) => (
              <option key={printer.product_name} value={printer.product_id}>
                {printer.product_name} 
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* 打印内容区域 */}
      <div className="card">
        <h2>打印内容</h2>
        <div className="form-group">
          <label>HTML内容:</label>
          <textarea
            value={printContent}
            onChange={(e) => setPrintContent(e.target.value)}
            rows={5}
            disabled={loading}
          />
        </div>
        <div className="checkbox-group">
          <input
            type="checkbox"
            id="silentPrint"
            checked={isSilentPrint}
            onChange={(e) => setIsSilentPrint(e.target.checked)}
            disabled={loading}
          />
          <label htmlFor="silentPrint">静默打印（无需确认）</label>
        </div>
        <button
          onClick={handlePrint}
          disabled={loading || printers.length === 0}
        >
          打印文档
        </button>
      </div>

      {/* USB设备区域 */}
      <div className="card">
        <h2>USB设备管理</h2>
        <button onClick={fetchUsbDevices} disabled={loading}>
          刷新USB设备列表
        </button>
        <div className="usb-devices-list">
          {usbDevices.length === 0 ? (
            <p>未找到USB设备</p>
          ) : (
            <div>
              <h3>连接的USB设备 ({usbDevices.length})</h3>
              {usbDevices.map((device, index) => (
                <div key={index} className="usb-device-item">
                  <div className="device-name">
                    <strong>{device.device_name}</strong>
                  </div>
                  <div className="device-details">
                    <p>
                      厂商ID: 0x{device.vendor_id.toString(16).toUpperCase()}
                    </p>
                    <p>
                      产品ID: 0x{device.product_id.toString(16).toUpperCase()}
                    </p>
                    <p>设备类别: {device.device_class}</p>
                    {device.manufacturer_name && (
                      <p>制造商: {device.manufacturer_name}</p>
                    )}
                    {device.product_name && (
                      <p>产品名称: {device.product_name}</p>
                    )}
                    {device.serial_number && (
                      <p>序列号: {device.serial_number}</p>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* 拍照区域 */}
      <div className="card">
        <h2>拍照功能</h2>
        <button onClick={handleTakePhoto} disabled={loading || isCapturing}>
          {isCapturing ? "拍照中..." : "拍照"}
        </button>

        {isCapturing && (
          <div className="capture-status">
            <p>{captureMessage || "请使用相机拍照，完成后返回应用"}</p>
          </div>
        )}

        {photoUri && (
          <div className="photo-preview">
            <h3>照片预览</h3>
            <img
              src={photoUri}
              alt="拍照预览"
              style={{ maxWidth: "100%", height: "auto" }}
            />
          </div>
        )}
      </div>

      {/* 状态消息 */}
      {message && <div className="message">{message}</div>}
    </div>
  )
}

export default App
