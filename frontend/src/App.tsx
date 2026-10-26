import { useEffect, useMemo, useState } from 'react'
import { FileText, FileDown, FileCheck2, RefreshCw, Download, FileType2, ShieldCheck, CircleAlert, CheckCircle2 } from 'lucide-react'

type Field = { key: string; label: string; defaultValue: string }
type Doc = { id:number; templateName:string; pdfFileName:string; docxFileName:string; createdAt:string; downloadUrl:string; docxUrl:string }
const api = async (url:string, init?:RequestInit) => { const r=await fetch('/api'+url,{headers:{'Content-Type':'application/json'},...init}); const j=await r.json().catch(()=>({error:'请求失败'})); if(!r.ok) throw new Error(j.message||j.error||'请求失败'); return j }
const groupLabel = (key:string) => key.startsWith('seller.') ? '卖方信息' : key.startsWith('buyer.') ? '买方信息' : key.startsWith('product.') ? '煤炭与价格' : '合同与交付'
export default function App(){
 const [fields,setFields]=useState<Field[]>([]), [data,setData]=useState<Record<string,string>>({}), [docs,setDocs]=useState<Doc[]>([]), [busy,setBusy]=useState(false), [notice,setNotice]=useState(''), [error,setError]=useState(false), [tab,setTab]=useState<'form'|'records'>('form'), [templateInfo,setTemplateInfo]=useState<any>(null)
 const refresh=async()=>{try{const [f,d,t]=await Promise.all([api('/contract-fields'),api('/documents'),api('/contract-template')]);setFields(f);setDocs(d);setTemplateInfo(t);setData(old=>{const next={...Object.fromEntries(f.map((x:Field)=>[x.key,x.defaultValue])),...old};return next})}catch(e:any){setError(true);setNotice(e.message)}}
 useEffect(()=>{refresh()},[])
 const groups=useMemo(()=>{const m=new Map<string,Field[]>();fields.forEach(f=>{const g=groupLabel(f.key);m.set(g,[...(m.get(g)||[]),f])});return [...m.entries()]},[fields])
 const setField=(k:string,v:string)=>setData(d=>({...d,[k]:v}))
 const missing=fields.filter(f=>!String(data[f.key]||'').trim())
 const generate=async()=>{setBusy(true);setNotice('');try{const r=await api('/documents',{method:'POST',body:JSON.stringify({data})});setNotice(r.message);setError(false);await refresh();window.open(r.downloadUrl,'_blank','noopener,noreferrer')}catch(e:any){setError(true);setNotice(e.message)}finally{setBusy(false)}}
 return <div className="appShell">
  <aside className="sidebar"><div className="brand"><div className="brandIcon"><FileText size={22}/></div><div><b>合同模板工作台</b><small>DOCX → PDF WORKFLOW</small></div></div><div className="sideLabel">工作空间</div><button className={tab==='form'?'navItem active':'navItem'} onClick={()=>setTab('form')}><FileType2 size={17}/>合同数据填写</button><button className={tab==='records'?'navItem active':'navItem'} onClick={()=>setTab('records')}><FileCheck2 size={17}/>生成记录 <span>{docs.length}</span></button><div className="sideBottom"><div className="safeTag"><ShieldCheck size={16}/>原始模板保留</div><p>当前为本地 Demo。生成文件保存到本地 storage 目录，数据库记录生成信息。</p></div></aside>
  <main className="mainArea"><header className="pageHeader"><div><div className="breadcrumb">合同模板 / {tab==='form'?'填写与生成':'生成记录'}</div><h1>{tab==='form'?'煤炭买卖合同':'生成记录'}</h1><p>{tab==='form'?'基于你提供的 DOCX 原始合同模板填充字段，并转换为 PDF。':'查看生成的 PDF 和可编辑 DOCX 文件。'}</p></div><button className="iconBtn" onClick={refresh} title="刷新数据"><RefreshCw size={17}/></button></header>
   {notice&&<div className={error?'notice error':'notice'}>{error?<CircleAlert size={17}/>:<CheckCircle2 size={17}/>}<span>{notice}</span><button onClick={()=>setNotice('')}>×</button></div>}
   {tab==='form'?<>
    <section className="summaryCard"><div className="docBadge"><FileText size={25}/></div><div className="summaryText"><div className="eyebrow">SOURCE DOCX TEMPLATE</div><h2>煤炭买卖合同</h2><p>contract-template-v2.docx · 7 页 · 原始条款与版式作为转换基础</p></div><div className="summaryStatus"><span className="greenDot"/>已加载模板</div></section>
    <div className="callout"><b>本版验证目标</b><span>先验证 Word 占位符替换、中文 PDF 转换和原版式保留。模板拖拽设计、审批和企业档案自动带入暂不包含。</span></div>
    <div className="formHeader"><div><h2>合同字段</h2><p>请修改示例数据。字段与原始 Word 中的占位符一一对应。</p></div><div className="fieldCount">{fields.length} 个字段 · {missing.length} 个未填写</div></div>
    <div className="formGrid">{groups.map(([group,items])=><section className="formCard" key={group}><div className="cardTitle"><span className="cardNumber">{String(groups.findIndex(g=>g[0]===group)+1).padStart(2,'0')}</span><h3>{group}</h3><span>{items.length} 项</span></div><div className="inputsGrid">{items.map(f=><label className="field" key={f.key}><span>{f.label}</span><input value={data[f.key]??''} onChange={e=>setField(f.key,e.target.value)} placeholder={'填写'+f.label}/><small>{'{<$. '+f.key+' >}'.replace('$. ','$.').replace(' >','>')}</small></label>)}</div></section>)}</div>
    <section className="generatePanel"><div><h3>生成合同文件</h3><p>系统会同时生成 PDF 和可编辑 DOCX，并记录本次字段快照。</p><div className="pipeline"><span>填写数据</span><i>→</i><span>替换 DOCX 占位符</span><i>→</i><span>LibreOffice 转 PDF</span></div></div><button className="primaryBtn" disabled={busy||fields.length===0} onClick={generate}>{busy?<RefreshCw className="spin" size={17}/>:<Download size={17}/>}{busy?'正在生成…':'生成 PDF 合同'}</button></section>
   </>:<section className="recordsCard"><div className="recordsHead"><div><h2>生成文件</h2><p>点击下载 PDF 进行版式核对，也可以下载 DOCX 检查字段替换结果。</p></div><span className="fieldCount">{docs.length} 条记录</span></div>{docs.length===0?<div className="emptyState"><FileText size={28}/><b>暂无生成记录</b><span>填写合同字段并生成文件后，记录会显示在这里。</span></div>:docs.map(d=><div className="docRow" key={d.id}><div className="pdfIcon"><FileText size={21}/></div><div className="docDetails"><b>{d.templateName}</b><span>{d.pdfFileName}</span><small>{d.createdAt}</small></div><div className="docActions"><a href={d.downloadUrl}><Download size={15}/>下载 PDF</a><a href={d.docxUrl}><FileType2 size={15}/>下载 DOCX</a></div></div>)}</section>}
   <footer><span>合同模板工作台 · Demo</span><span>React · Spring Boot · Apache POI · LibreOffice · MySQL</span></footer>
  </main>
 </div>
}
