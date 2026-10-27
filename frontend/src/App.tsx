import { useEffect, useState } from 'react'
import { FileText, FileDown, FileCheck2, RefreshCw, Download, FileType2, ShieldCheck, CircleAlert, CheckCircle2, Save, Copy } from 'lucide-react'
import OnlyOfficeEditor from './OnlyOfficeEditor'

type Field = { key: string; label: string; defaultValue: string }
type Doc = { id:number; templateName:string; pdfFileName:string; docxFileName:string; createdAt:string; downloadUrl:string; docxUrl:string }
type Template = { id:string; name:string; isDefault:boolean }

const api = async (url:string, init?:RequestInit) => {
  const headers = init?.body instanceof FormData ? {} : {'Content-Type':'application/json'}
  const r = await fetch('/api'+url,{headers:{...headers,...init?.headers},...init})
  const j = await r.json().catch(()=>({error:'请求失败'}))
  if(!r.ok) throw new Error(j.message||j.error||'请求失败')
  return j
}

export default function App(){
  const [templateName,setTemplateName] = useState('煤炭买卖合同版本')
  const [saveBusy,setSaveBusy] = useState(false)
  const [fields,setFields] = useState<Field[]>([])
  const [data,setData] = useState<Record<string,string>>({})
  const [docs,setDocs] = useState<Doc[]>([])
  const [templates,setTemplates] = useState<Template[]>([])
  const [templateId,setTemplateId] = useState('default')
  const [previewUrl,setPreviewUrl] = useState('')
  const [previewBusy,setPreviewBusy] = useState(false)
  const [busy,setBusy] = useState(false)
  const [notice,setNotice] = useState('')
  const [error,setError] = useState(false)
  const [tab,setTab] = useState<'form'|'records'>('form')
  const [loaded,setLoaded] = useState(false)
  const [editorDirty,setEditorDirty] = useState(false)
  const [reloadToken,setReloadToken] = useState(0)

  const refresh = async () => {
    try {
      const [f,d,t] = await Promise.all([api('/contract-fields'),api('/documents'),api('/templates')])
      setFields(f)
      setDocs(d)
      setTemplates(t)
      setData(old => Object.keys(old).length ? old : Object.fromEntries(f.map((x:Field)=>[x.key,String(x.defaultValue ?? '')])))
      setLoaded(true)
    } catch(e:any) {
      setError(true)
      setNotice(e.message)
    }
  }

  useEffect(()=>{ void refresh() },[])

  useEffect(()=>{
    setPreviewUrl('')
    setEditorDirty(false)
  },[templateId])

  const selectedTemplate = templates.find(t=>t.id===templateId)
  const emptyFieldCount = fields.filter(f=>!(data[f.key] ?? '').trim()).length
  const dataReady = Object.keys(data).length > 0

  const selectTemplate = (id:string) => {
    setTemplateId(id)
    const found = templates.find(t=>t.id===id)
    if(found) setTemplateName(found.name + ' - 新版本')
  }

  const reloadEditor = async () => {
    if(editorDirty && !window.confirm('重新载入会丢弃编辑器中尚未保存的修改，继续？')) return
    setNotice('正在重新载入编辑器与后端字段值…')
    setError(false)
    try {
      const f = await api('/contract-fields')
      setFields(f)
      setData(Object.fromEntries(f.map((x:Field)=>[x.key,String(x.defaultValue ?? '')])))
      setEditorDirty(false)
      setReloadToken(x=>x+1)
      setNotice('已重新载入编辑器，字段值来自后端。')
    } catch(e:any) {
      setError(true)
      setNotice(e.message)
    }
  }

  const updatePreview = async () => {
    setPreviewBusy(true)
    setNotice('')
    try {
      const r = await api('/preview',{method:'POST',body:JSON.stringify({templateId,data})})
      setPreviewUrl(r.url+'?v='+Date.now())
      setNotice(r.message)
      setError(false)
    } catch(e:any) {
      setError(true)
      setNotice(e.message)
    } finally {
      setPreviewBusy(false)
    }
  }

  const generate = async () => {
    if(editorDirty && !window.confirm('编辑器还有未保存的模板修改，生成将使用已保存的模板版本。继续？')) return
    setBusy(true)
    setNotice('')
    try {
      const r = await api('/documents',{method:'POST',body:JSON.stringify({templateId,data})})
      setNotice(r.message)
      setError(false)
      const d = await api('/documents')
      setDocs(d)
      window.open(r.downloadUrl,'_blank','noopener,noreferrer')
    } catch(e:any) {
      setError(true)
      setNotice(e.message)
    } finally {
      setBusy(false)
    }
  }

  const saveTemplateVersion = async () => {
    setSaveBusy(true)
    setNotice('正在请求 ONLYOFFICE 完成保存并生成版本…')
    try {
      const r = await api('/onlyoffice/versions/'+templateId,{method:'POST',body:JSON.stringify({name:templateName})})
      const list = await api('/templates')
      setTemplates(list)
      setTemplateId(r.id)
      setTemplateName(r.name)
      setPreviewUrl('')
      setEditorDirty(false)
      setNotice(r.message || '模板版本已保存，编辑器中的业务值已还原为占位符。')
      setError(false)
    } catch(e:any) {
      setError(true)
      setNotice(e.message)
    } finally {
      setSaveBusy(false)
    }
  }

  return <div className="appShell">
    <aside className="sidebar">
      <div className="brand"><div className="brandIcon"><FileText size={22}/></div><div><b>合同模板工作台</b><small>DOCX → PDF WORKFLOW</small></div></div>
      <div className="sideLabel">工作空间</div>
      <button className={tab==='form'?'navItem active':'navItem'} onClick={()=>setTab('form')}><FileType2 size={17}/>合同模板编辑</button>
      <button className={tab==='records'?'navItem active':'navItem'} onClick={()=>setTab('records')}><FileCheck2 size={17}/>生成记录 <span>{docs.length}</span></button>
      <div className="sideBottom"><div className="safeTag"><ShieldCheck size={16}/>占位符自动还原</div><p>编辑器里显示业务值；保存模板版本时后端会还原成占位符，不会把值写死进模板。</p></div>
    </aside>
    <main className="mainArea">
      <header className="pageHeader">
        <div>
          <div className="breadcrumb">合同模板 / {tab==='form'?'模板编辑与合同生成':'生成记录'}</div>
          <h1>{tab==='form'?'模板编辑与合同生成':'生成记录'}</h1>
          <p>{tab==='form'?'在真实 DOCX 页面中直接编辑；字段值来自后端，保存时仍保留占位符。':'查看生成的 PDF 和可编辑 DOCX 文件。'}</p>
        </div>
        <button className="iconBtn" onClick={refresh} title="刷新数据"><RefreshCw size={17}/></button>
      </header>

      {notice&&<div className={error?'notice error':'notice'}>{error?<CircleAlert size={17}/>:<CheckCircle2 size={17}/>}<span>{notice}</span><button onClick={()=>setNotice('')}>×</button></div>}

      {tab==='form'?<>
        <section className="summaryCard">
          <div className="docBadge"><FileText size={25}/></div>
          <div className="summaryText">
            <div className="eyebrow">SOURCE DOCX TEMPLATE</div>
            <h2>选择合同模板</h2>
            <p>编辑器直接打开当前模板版本；生成和 PDF 预览也使用这个版本。</p>
            <div className="templateControls">
              <select aria-label="选择模板" value={templateId} onChange={e=>selectTemplate(e.target.value)}>
                {templates.map(t=><option key={t.id} value={t.id}>{t.name}</option>)}
              </select>
              <input aria-label="模板版本名称" className="templateNameInput" value={templateName} onChange={e=>setTemplateName(e.target.value)} placeholder="输入保存的模板名称"/>
              <button className="secondaryBtn" disabled={saveBusy} onClick={saveTemplateVersion}>{saveBusy?<RefreshCw className="spin" size={14}/>:<Save size={14}/>}保存模板版本</button>
              <button className="secondaryBtn" disabled={saveBusy} onClick={()=>setTemplateName((selectedTemplate?.name||'合同模板')+' - 新版本')}><Copy size={14}/>另存为新版本</button>
            </div>
          </div>
          <div className="summaryStatus"><span className="greenDot"/>{fields.length} 个字段 · {emptyFieldCount} 个无值</div>
        </section>

        <div className="callout">
          <b>编辑规则</b>
          <span>编辑器显示后端当前字段值；保存模板版本时自动还原为 <code>{'{<$.key>}'}</code> 占位符。没有值的字段在页面中留空，保存后仍然保留占位符。编辑器的自动保存只保存在编辑器会话中；生成和预览使用“保存模板版本”生成的版本。</span>
        </div>

        <div className="formHeader clauseHeader">
          <div><h2>模板原位编辑</h2><p>直接在真实 DOCX 页面上改文字；字段区域由后端值填充，无值则留空。</p></div>
          <div className="previewActions">
            <button className="primaryBtn" disabled={saveBusy} onClick={saveTemplateVersion}>{saveBusy?<RefreshCw className="spin" size={14}/>:<Save size={14}/>}保存模板版本</button>
            <button className="secondaryBtn" onClick={reloadEditor}><RefreshCw size={14}/>重新载入</button>
            <button className="secondaryBtn" disabled={previewBusy} onClick={updatePreview}>{previewBusy?<RefreshCw className="spin" size={14}/>:<FileText size={14}/>}更新 PDF 预览</button>
          </div>
        </div>

        <div className="clauseNotice"><ShieldCheck size={17}/><span><b>字段保护</b> · 共 {fields.length} 个字段，当前 {emptyFieldCount} 个无值；保存时所有字段都会还原为占位符，不会把业务值写进模板。</span></div>

        {editorDirty && <div className="callout"><b>未保存修改</b><span>编辑器里还有未保存的模板修改；生成和预览使用已保存的模板版本，请先点击“保存模板版本”。</span></div>}

        {!loaded ? <p className="paragraphLoading">正在读取后端字段…</p> :
          !dataReady && fields.length>0 ? <p className="paragraphLoading">正在准备字段值…</p> :
          <OnlyOfficeEditor
            templateId={templateId}
            templateName={selectedTemplate?.name || '合同模板'}
            values={data}
            reloadToken={reloadToken}
            onDirty={setEditorDirty}
          />}

        {previewUrl && <section className="pdfPreviewWorkspace" id="pdf-preview">
          <div className="pdfPreviewToolbar"><span><FileText size={15}/><b>合同 PDF 预览</b></span><span>使用已保存的模板版本与当前后端字段值</span></div>
          <iframe className="pdfPreviewFrame" src={previewUrl} title="合同 PDF 预览"/>
        </section>}

        <section className="generatePanel">
          <div>
            <h3>生成合同文件</h3>
            <p>使用当前选中的模板版本，把后端字段值替换到占位符，生成 DOCX 并转换 PDF。编辑器自动保存不会写回服务器或创建版本，编辑后请先“保存模板版本”。</p>
            <div className="pipeline"><span>选择模板版本</span><i>→</i><span>读取后端字段</span><i>→</i><span>替换占位符</span><i>→</i><span>转换 PDF</span></div>
          </div>
          <button className="primaryBtn" disabled={busy||fields.length===0} onClick={generate}>{busy?<RefreshCw className="spin" size={17}/>:<Download size={17}/>}{busy?'正在生成…':'生成 PDF 合同'}</button>
        </section>
      </>:<section className="recordsCard">
        <div className="recordsHead"><div><h2>生成文件</h2><p>每条记录对应一次独立生成，包含当次业务数据和模板快照。</p></div><span className="fieldCount">{docs.length} 条记录</span></div>
        {docs.length===0?<div className="emptyState"><FileText size={28}/><b>暂无生成记录</b><span>填写合同字段并生成文件后，记录会显示在这里。</span></div>:docs.map(d=><div className="docRow" key={d.id}><div className="pdfIcon"><FileText size={21}/></div><div className="docDetails"><b>{d.templateName}</b><span>{d.pdfFileName}</span><small>{d.createdAt}</small></div><div className="docActions"><a href={d.downloadUrl}><Download size={15}/>下载 PDF</a><a href={d.docxUrl}><FileDown size={15}/>下载 DOCX</a></div></div>)}
      </section>}

      <footer><span>合同模板工作台 · Demo</span><span>React · Spring Boot · Apache POI · ONLYOFFICE · LibreOffice · MySQL</span></footer>
    </main>
  </div>
}
