import { useState, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import Vconsole from 'vconsole'
import "./App.css";


new Vconsole()
// 定义打印机类型
interface Printer {
  name: string;
  is_default: boolean;
}

// 定义照片信息类型
interface PhotoInfo {
  path: string;
  uri: string;
}

function App() {
  // 状态管理
  const [printers, setPrinters] = useState<Printer[]>([]);
  const [selectedPrinter, setSelectedPrinter] = useState<string>("");
  const [printContent, setPrintContent] = useState<string>("<h1>测试打印内容</h1><p>这是一个测试打印文档。</p>");
  const [isSilentPrint, setIsSilentPrint] = useState<boolean>(false);
  const [photoUri, setPhotoUri] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [message, setMessage] = useState<string>("");

  // 获取连接的打印机列表
  const fetchPrinters = async () => {
    try {
      console.log('正在获取打印机列表..........')
      setLoading(true);
      setMessage("正在获取打印机列表...");
      const result = await invoke<Printer[]>("get_connected_printers");
      setPrinters(result);
      console.log(result, 'result------------')
      if (result.length > 0) {
        // 设置默认打印机
        const defaultPrinter = result.find(p => p.is_default) || result[0];
        setSelectedPrinter(defaultPrinter.name);
      }
      setMessage(`找到 ${result.length} 台打印机`);
    } catch (error) {
      console.error("获取打印机列表失败:", error);
      setMessage(`获取打印机列表失败: ${error}`);
    } finally {
      setLoading(false);
    }
  };

  // 打印文档
  const handlePrint = async () => {
    try {
      setLoading(true);
      setMessage("正在打印...");
      await invoke<string>("print_document", { 
        content: printContent, 
        silent: isSilentPrint 
      });
      setMessage("打印成功");
    } catch (error) {
      console.error("打印失败:", error);
      setMessage(`打印失败: ${error}`);
    } finally {
      setLoading(false);
    }
  };

  // 拍照
  const handleTakePhoto = async () => {
    try {
      setLoading(true);
      setMessage("正在打开相机...");
      const photo = await invoke<PhotoInfo>("take_photo");
      console.log(photo, 'photo-----------------')
      setPhotoUri(photo.uri);
      setMessage("拍照成功");
    } catch (error) {
      console.error("拍照失败:", error);
      setMessage(`拍照失败: ${error}`);
    } finally {
      setLoading(false);
    }
  };

  // 组件挂载时获取打印机列表
  useEffect(() => {
    fetchPrinters();
  }, []);

  return (
    <div className="container">
      <h1>打印和拍照应用</h1>

      {/* 打印机选择区域 */}
      <div className="card">
        <h2>打印机设置</h2>
        <button onClick={fetchPrinters} disabled={loading}>
          刷新打印机列表
        </button>
        <div className="form-group">
          <label>选择打印机:</label>
          <select
            value={selectedPrinter}
            onChange={(e) => setSelectedPrinter(e.target.value)}
            disabled={printers.length === 0 || loading}
          >
            {printers.length === 0 && <option value="">无可用打印机</option>}
            {printers.map((printer) => (
              <option key={printer.name} value={printer.name}>
                {printer.name} {printer.is_default ? "(默认)" : ""}
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
        <button onClick={handlePrint} disabled={loading || printers.length === 0}>
          打印文档
        </button>
      </div>

      {/* 拍照区域 */}
      <div className="card">
        <h2>拍照功能</h2>
        <button onClick={handleTakePhoto} disabled={loading}>
          拍照
        </button>
        {photoUri && (
          <div className="photo-preview">
            <h3>照片预览</h3>
            <img src={photoUri} alt="拍照预览" />
          </div>
        )}
      </div>

      {/* 状态消息 */}
      {message && <div className="message">{message}</div>}
    </div>
  );
}

export default App;
