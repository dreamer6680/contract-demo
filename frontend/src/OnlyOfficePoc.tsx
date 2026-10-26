import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, RefreshCw, ShieldAlert } from 'lucide-react'

declare global {
  interface Window {
    DocsAPI?: { DocEditor: new (id: string, config: Record<string, unknown>) => { destroyEditor?: () => void } }
  }
}

type EditorConfigResponse = { documentServerUrl: string; config: Record<string, unknown> }

export default function OnlyOfficePoc({ onBack }: { onBack: () => void }) {
  const editorRef = useRef<{ destroyEditor?: () => void } | null>(null)
  const [status, setStatus] = useState('正在连接 ONLYOFFICE Docs…')
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    let script: HTMLScriptElement | null = null

    const start = async () => {
      try {
        const response = await fetch('/api/onlyoffice/config/default')
        const result = await response.json() as EditorConfigResponse & { message?: string }
        if (!response.ok) throw new Error(result.message || '获取编辑器配置失败')

        const scriptUrl = result.documentServerUrl.replace(/\/$/, '') + '/web-apps/apps/api/documents/api.js'
        const existing = document.querySelector<HTMLScriptElement>('script[data-onlyoffice-api="true"]')
        if (!existing) {
          script = document.createElement('script')
          script.src = scriptUrl
          script.async = true
          script.dataset.onlyofficeApi = 'true'
          document.head.appendChild(script)
          await new Promise<void>((resolve, reject) => {
            script!.onload = () => resolve()
            script!.onerror = () => reject(new Error('无法加载 ONLYOFFICE Docs API。请确认 Document Server 已启动并可从浏览器访问。'))
          })
        } else if (!window.DocsAPI) {
          await new Promise<void>((resolve, reject) => {
            existing.addEventListener('load', () => resolve(), { once: true })
            existing.addEventListener('error', () => reject(new Error('ONLYOFFICE Docs API 加载失败')), { once: true })
          })
        }

        if (cancelled) return
        if (!window.DocsAPI) throw new Error('ONLYOFFICE Docs API 未初始化')
        editorRef.current = new window.DocsAPI.DocEditor('onlyoffice-editor', result.config)
        setStatus('编辑器已初始化。当前为基础接入验证，尚未启用占位符锁定。')
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : String(e))
          setStatus('编辑器启动失败')
        }
      }
    }

    void start()
    return () => {
      cancelled = true
      editorRef.current?.destroyEditor?.()
      editorRef.current = null
    }
  }, [])

  return <div className="onlyOfficePocPage">
    <header className="onlyOfficePocHeader">
      <button className="secondaryBtn" onClick={onBack}><ArrowLeft size={15} />返回合同工作台</button>
      <div><b>ONLYOFFICE 原生 DOCX 编辑验证</b><span>{status}</span></div>
    </header>
    <div className="onlyOfficePocNotice"><ShieldAlert size={17} /><span>这是开发验证页。当前接入用于验证原生 DOCX 编辑和保存链路；占位符内容锁定、保存后完整性校验尚未完成，暂不要用此页面编辑正式合同模板。</span></div>
    {error && <div className="onlyOfficePocError"><b>启动失败</b><span>{error}</span><small>请先运行：<code>docker compose --profile onlyoffice up -d onlyoffice</code>，并确认浏览器可以访问 http://localhost:8081/healthcheck。</small></div>}
    <div className="onlyOfficePocEditor"><div id="onlyoffice-editor" /></div>
  </div>
}
