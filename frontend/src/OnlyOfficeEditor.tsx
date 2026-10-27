import { useEffect, useRef, useState } from 'react'
import { FilePenLine, ShieldCheck } from 'lucide-react'

declare global {
  interface Window {
    DocsAPI?: { DocEditor: new (id: string, config: Record<string, unknown>) => { destroyEditor?: () => void } }
  }
}

type EditorConfigResponse = {
  documentServerUrl: string
  config: Record<string, unknown>
  valuesHash?: string
  message?: string
}

type Props = {
  templateId: string
  templateName: string
  values: Record<string, string>
  reloadToken?: number
  onDirty?: (dirty: boolean) => void
}

export default function OnlyOfficeEditor({ templateId, templateName, values, reloadToken = 0, onDirty }: Props) {
  const editorRef = useRef<{ destroyEditor?: () => void } | null>(null)
  const [status, setStatus] = useState('正在连接 ONLYOFFICE…')
  const [error, setError] = useState('')
  const [ready, setReady] = useState(false)
  const [dirty, setDirty] = useState(false)
  const valuesKey = JSON.stringify(values)

  useEffect(() => {
    let cancelled = false
    let script: HTMLScriptElement | null = null
    setError('')
    setReady(false)
    setDirty(false)
    onDirty?.(false)
    setStatus('正在加载模板与字段值…')

    const ensureApi = async (documentServerUrl: string) => {
      const scriptUrl = documentServerUrl.replace(/\/$/, '') + '/web-apps/apps/api/documents/api.js'
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
        return
      }
      if (!window.DocsAPI) {
        await new Promise<void>((resolve, reject) => {
          existing.addEventListener('load', () => resolve(), { once: true })
          existing.addEventListener('error', () => reject(new Error('ONLYOFFICE Docs API 加载失败')), { once: true })
        })
      }
    }

    const start = async () => {
      try {
        const response = await fetch('/api/onlyoffice/config/' + encodeURIComponent(templateId), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ data: values }),
        })
        const result = await response.json() as EditorConfigResponse
        if (!response.ok) throw new Error(result.message || '获取编辑器配置失败')
        await ensureApi(result.documentServerUrl)
        if (cancelled) return
        if (!window.DocsAPI) throw new Error('ONLYOFFICE Docs API 未初始化')

        editorRef.current?.destroyEditor?.()
        editorRef.current = new window.DocsAPI.DocEditor('onlyoffice-editor', {
          ...result.config,
          events: {
            onDocumentReady: () => {
              if (cancelled) return
              setReady(true)
              setStatus('编辑器已就绪 · 字段值来自后端')
            },
            onDocumentStateChange: (event: { data?: boolean }) => {
              if (cancelled) return
              const nextDirty = Boolean(event?.data)
              setDirty(nextDirty)
              onDirty?.(nextDirty)
              setStatus(nextDirty ? '有未保存修改' : '已同步到编辑器，点击保存模板版本写回服务器')
            },
            onError: (event: { data?: { description?: string } }) => {
              if (cancelled) return
              setError(event?.data?.description || 'ONLYOFFICE 编辑器发生错误')
            },
          },
        })
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
  }, [templateId, valuesKey, reloadToken])

  return <section className="onlyOfficeEmbed">
    <div className="onlyOfficeEmbedBar">
      <div className="onlyOfficeEmbedTitle"><FilePenLine size={17}/><b>{templateName || '合同模板'}</b><span>ONLYOFFICE</span></div>
      <div className="onlyOfficeEmbedState">
        <span className={dirty ? 'stateDirty' : ready ? 'stateReady' : 'stateLoading'}>{ready ? (dirty ? '编辑中' : '已就绪') : '加载中'}</span>
        <span>{status}</span>
      </div>
    </div>
    <div className="onlyOfficeValueNote"><ShieldCheck size={15}/><span>页面中显示的是后端字段值；保存模板版本时会自动还原为 <code>{'{<$.key>}'}</code> 占位符，无值字段保持为空。</span></div>
    {error && <div className="onlyOfficeEmbedError"><b>编辑器启动失败</b><span>{error}</span><small>请确认 ONLYOFFICE Document Server 已启动（docker compose --profile onlyoffice up -d onlyoffice）。</small></div>}
    <div className="onlyOfficeEmbedEditor"><div id="onlyoffice-editor"/></div>
  </section>
}
