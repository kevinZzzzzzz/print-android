import React, { useState, useEffect, useRef } from "react"
import { invoke } from "@tauri-apps/api/core"
import { Space, Button, Input, Toast, Form, Picker, Cascader } from "antd-mobile"
import Vconsole from "vconsole"
import { setting } from "./setting"
import { RightOutline, UploadOutline } from "antd-mobile-icons"
import './App.css'

new Vconsole()
// 定义打印机类型
interface Printer {
  device_name: string
  product_name: string
}
function App(props: any) {
  const [printList, setPrintList] = useState<any>([])
  const [setPrint, setSetPrint] = useState<any>("")
  const [host, setHost] = useState<string>(setting.host)
  const [sseHost, setSseHost] = useState<string>(setting.sseHost)
  const [clientId, setClientId] = useState<string>(setting.clientId)
  const printUrl = useRef<string>("")
  const [visible, setVisible] = useState<boolean>(false)

  // 获取连接的打印机列表
  const fetchPrinters = async () => {
    try {
      Toast.show({
        icon: "loading",
        content: "加载中…",
      })
      const result = await invoke<Printer[]>("get_usb_devices")
      if (result && result.length > 0) {
        setPrintList(result)
        setSetPrint(result[0].device_name)
      }
      Toast.show({
        icon: "success",
        content: "获取打印机列表成功",
      })
    } catch (error) {
      setPrintList([])
      Toast.show({
        icon: "error",
        content: "获取打印机列表失败",
      })
    }
  }

  const handlePrint = async () => {
    console.log(setPrint, 'setPrint000000000')
    if (setPrint === "") {
      Toast.show({
        icon: "error",
        content: "请选择打印机",
      })
      return
    }
    printUrl.current = "http://192.168.120.178:8080/test.pdf"
    try {
      const result: any = await invoke<Printer[]>("download_pdf", {
        url: printUrl.current
      })
      console.log(result, 'result000000000')
      setTimeout(async () => {
        const resultObj = JSON.parse(result)
        const response = await invoke<string>("print_document", {
          devicePath: setPrint,  // 使用实际的设备路径
          uri: `${resultObj.logs[0]}.pdf`,
        })
        console.log(JSON.parse(response), 'response000000000')
      }, 2000);
      
    } catch (error) {
      console.log(error, 'error000000')
    }
    
  }
  useEffect(() => {
    fetchPrinters()
  }, [])

  return (
    <div>
      <Form layout="horizontal">
        <Form.Item
          label="设备ID："
          extra={
            <Button
              onClick={() => {
                window.location.reload()
              }}
            >
              保存
            </Button>
          }
        >
          <Input
            value={clientId}
            type="number"
            placeholder="请输入设备ID"
            onChange={(val: any) => {
              setClientId(val)
              window.localStorage.setItem("clientId", val)
            }}
          />
        </Form.Item>
        <Form.Item
          label="本地打印机："
        >
          <select
            id="printerSelect"
            value={setPrint}
            onChange={(e: any) => {
              setSetPrint(e.target.value)
            }}
          >
            {printList.length === 0 && <option value="">无可用打印机</option>}
            {printList.map((printer: any) => (
              <option key={printer.product_name} value={printer.device_name}>
                {printer.product_name} 
              </option>
            ))}
          </select>
        </Form.Item>
        
        <Form.Item
          label="服务端地址："
          extra={
            <Button
              onClick={() => {
                window.location.reload()
              }}
            >
              保存
            </Button>
          }
        >
          <Input
            value={host}
            type="number"
            placeholder="请输入服务端地址"
            onChange={(e: any) => {
              setHost(e.target.value)
              window.localStorage.setItem("host", e.target.value);
            }}
          />
        </Form.Item>
        <Form.Item
          label="SSE地址："
          extra={
            <Button
              onClick={() => {
                window.location.reload()
              }}
            >
              保存
            </Button>
          }
        >
          <Input
            value={sseHost}
            type="number"
            placeholder="请输入SSE地址"
            onChange={(e: any) => {
              setSseHost(e.target.value)
              window.localStorage.setItem("sseHost", e.target.value);
            }}
          />
        </Form.Item>
        <Form.Item>
          <Button block color='primary' fill='solid' onClick={() => {
            handlePrint()
          }}>
            立即打印
          </Button>
        </Form.Item>
      </Form>
    </div>
  )
}
export default App
