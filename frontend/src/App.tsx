import { useEffect, useMemo, useState } from 'react'
import { FileText, FileDown, FileCheck2, RefreshCw, Download, FileType2, ShieldCheck, CircleAlert, CheckCircle2, Plus, RotateCcw, PencilLine, GripVertical } from 'lucide-react'

type Field = { key: string; label: string; defaultValue: string }
type Clause = { id: string; title: string; description: string; content: string; anchors?: string[] }
type Doc = { id:number; templateName:string; pdfFileName:string; docxFileName:string; createdAt:string; downloadUrl:string; docxUrl:string }
const api = async (url:string, init?:RequestInit) => { const r=await fetch('/api'+url,{headers:{'Content-Type':'application/json'},...init}); const j=await r.json().catch(()=>({error:'请求失败'})); if(!r.ok) throw new Error(j.message||j.error||'请求失败'); return j }
const groupLabel = (key:string) => key.startsWith('seller.') ? '卖方信息' : key.startsWith('buyer.') ? '买方信息' : key.startsWith('product.') ? '煤炭与价格' : '合同与交付'
export default function App(){
 const [fields,setFields]=useState<Field[]>([]), [data,setData]=useState<Record<string,string>>({}), [docs,setDocs]=useState<Doc[]>([]), [clauses,setClauses]=useState<Clause[]>([]), [defaultClauses,setDefaultClauses]=useState<Clause[]>([]), [busy,setBusy]=useState(false), [notice,setNotice]=useState(''), [error,setError]=useState(false), [tab,setTab]=useState<'form'|'records'>('form'), [editorTab,setEditorTab]=useState<'data'|'clauses'>('data')
 const refresh=async()=>{try{const [f,d,c]=await Promise.all([api('/contract-fields'),api('/documents'),api('/contract-clauses')]);setFields(f);setDocs(d);setDefaultClauses(c);setClauses(old=>old.length?old:c);setData(old=>({...Object.fromEntries(f.map((x:Field)=>[x.key,x.defaultValue])),...old}))}catch(e:any){setError(true);setNotice(e.message)}}
 useEffect(()=>{refresh()},[])
 const groups=useMemo(()=>{const m=new Map<string,Field[]>();fields.forEach(f=>{const g=groupLabel(f.key);m.set(g,[...(m.get(g)||[]),f])});return [...m.entries()]},[fields])
 const setField=(k:string,v:string)=>setData(d=>({...d,[k]:v}))
 const updateClause=(id:string,content:string)=>setClauses(old=>old.map(c=>c.id===id?{...c,content}:c))
 const addClauseLine=(id:string)=>setClauses(old=>old.map(c=>c.id===id?{...c,content:(c.content.trimEnd()?c.content.trimEnd()+'\n':'')+'新增条款：请填写条款内容。'}:c))
 const missing=fields.filter(f=>!String(data[f.key]||'').trim())
 const resetClauses=()=>{setClauses(defaultClauses.map(c=>({...c})));setNotice('本次编辑已恢复为默认条款；不会修改公共模板。');setError(false)}
 const generate=async()=>{setBusy(true);setNotice('');try{const r=await api('/documents',{method:'POST',body:JSON.stringify({data,clauses:clauses.map(({id,content})=>({id,content}))})});setNotice(r.message);setError(false);const d=await api('/documents');setDocs(d);window.open(r.downloadUrl,'_blank','noopener,noreferrer')}catch(e:any){setError(true);setNotice(e.message)}finally{setBusy(false)}}
 return <div className="appShell">
  <aside className="sidebar"><div className="brand"><div className="brandIcon"><FileText size={22}/></div><div><b>合同模板工作台</b><small>DOCX → PDF WORKFLOW</small></div></div><div className="sideLabel">工作空间</div><button className={tab==='form'?'navItem active':'navItem'} onClick={()=>setTab('form')}><FileType2 size={17}/>合同填写与编辑</button><button className={tab==='records'?'navItem active':'navItem'} onClick={()=>setTab('records')}><FileCheck2 size={17}/>生成记录 <span>{docs.length}</span></button><div className="sideBottom"><div className="safeTag"><ShieldCheck size={16}/>本次编辑独立生效</div><p>固定条款仅用于本次生成，不会修改公共 DOCX 模板。每次重新进入页面时加载默认条款。</p></div></aside>
  <main className="mainArea"><header className="pageHeader"><div><div className="breadcrumb">合同模板 / {tab==='form'?'填写、编辑与生成':'生成记录'}</div><h1>{tab==='form'?'煤炭买卖合同':'生成记录'}</h1><p>{tab==='form'?'填写业务数据，并按本次合同需要修改或新增固定条款。':'查看生成的 PDF 和可编辑 DOCX 文件。'}</p></div><button className="iconBtn" onClick={refresh} title="刷新数据"><RefreshCw size={17}/></button></header>
   {notice&&<div className={error?'notice error':'notice'}>{error?<CircleAlert size={17}/>:<CheckCircle2 size={17}/>}<span>{notice}</span><button onClick={()=>setNotice('')}>×</button></div>}
   {tab==='form'?<>
    <section className="summaryCard"><div className="docBadge"><FileText size={25}/></div><div className="summaryText"><div className="eyebrow">SOURCE DOCX TEMPLATE</div><h2>煤炭买卖合同</h2><p>contract-template-v2.docx · 原始版式作为生成基础</p></div><div className="summaryStatus"><span className="greenDot"/>公共模板只读</div></section>
    <div className="callout"><b>编辑规则</b><span>动态占位符由后端业务数据填充；固定条款可以在本次生成前单独修改、追加条款。编辑只影响当前生成，不会覆盖原始模板或影响下一次生成。</span></div>
    <div className="editorTabs"><button className={editorTab==='data'?'editorTab active':'editorTab'} onClick={()=>setEditorTab('data')}><FileType2 size={16}/>业务数据 <span>{fields.length}</span></button><button className={editorTab==='clauses'?'editorTab active':'editorTab'} onClick={()=>setEditorTab('clauses')}><PencilLine size={16}/>本次条款编辑 <span>{clauses.length}</span></button></div>
    {editorTab==='data'?<>
      <div className="formHeader"><div><h2>合同字段</h2><p>这些字段对应 Word 模板中的动态占位符，生成时由后端填充。</p></div><div className="fieldCount">{fields.length} 个字段 · {missing.length} 个未填写</div></div>
      <div className="formGrid">{groups.map(([group,items])=><section className="formCard" key={group}><div className="cardTitle"><span className="cardNumber">{String(groups.findIndex(g=>g[0]===group)+1).padStart(2,'0')}</span><h3>{group}</h3><span>{items.length} 项</span></div><div className="inputsGrid">{items.map(f=><label className="field" key={f.key}><span>{f.label}</span><input value={data[f.key]??''} onChange={e=>setField(f.key,e.target.value)} placeholder={'填写'+f.label}/><small>{'{<$. '+f.key+' >}'.replace('$. ','$.').replace(' >','>')}</small></label>)}</div></section>)}</div>
    </>:<>
      <div className="formHeader clauseHeader"><div><h2>合同正文预览与编辑</h2><p>以合同纸张版式直接编辑正文。点击条款文字即可修改，页面用于近似预览，最终排版以生成的 PDF 为准。</p></div><button className="secondaryBtn" onClick={resetClauses}><RotateCcw size={14}/>恢复默认条款</button></div>
      <div className="clauseNotice"><ShieldCheck size={17}/><span><b>仅本次生效</b> · 修改不会写回公共模板。左侧切换“业务数据”编辑动态字段；这里直接编辑合同正文固定条款。</span></div>
      <div className="paperWorkspace"><div className="paperToolbar"><span><FileText size={15}/> 煤炭买卖合同 · 编辑预览</span><span>页面预览不等于最终 PDF</span></div><article className="contractPaper">
       <div className="paperTopline">合同编号：{data['contractNo']||'________________'}</div>
       <h1 className="paperMainTitle">煤炭买卖合同</h1>
       <div className="paperParties"><p>卖方（甲方）：{data['seller.name']||'________________'}</p><p>买方（乙方）：{data['buyer.name']||'________________'}</p><p>签订日期：{data['signDate']||'________________'}</p></div>
       <p className="paperIntro">甲乙双方本着平等、自愿、诚实信用的原则，就煤炭买卖事宜达成如下约定：</p>
       {clauses.map((c,index)=><section className="paperClause" key={c.id}><h3>{index+1}、{c.title}</h3><textarea aria-label={c.title} className="paperClauseEditor" value={c.content} onChange={e=>updateClause(c.id,e.target.value)} rows={Math.max(1,c.content.split('\n').length)} spellCheck={false}/><div className="paperClauseTools"><span>点击正文可直接编辑</span><button className="addClauseBtn" onClick={()=>addClauseLine(c.id)}><Plus size={13}/>追加一行</button></div></section>)}
       <div className="paperSignatures"><div><b>甲方（盖章）：</b><p>法定代表人/授权代表：</p><p>日期：　　 年　 月　 日</p></div><div><b>乙方（盖章）：</b><p>法定代表人/授权代表：</p><p>日期：　　 年　 月　 日</p></div></div>
      </article></div>
    </>}
    <section className="generatePanel"><div><h3>生成合同文件</h3><p>系统会将业务字段与本次编辑的条款合并，生成 DOCX 并转换为 PDF。</p><div className="pipeline"><span>填写业务数据</span><i>→</i><span>应用本次条款</span><i>→</i><span>替换占位符</span><i>→</i><span>转换 PDF</span></div></div><button className="primaryBtn" disabled={busy||fields.length===0} onClick={generate}>{busy?<RefreshCw className="spin" size={17}/>:<Download size={17}/>}{busy?'正在生成…':'生成 PDF 合同'}</button></section>
   </>:<section className="recordsCard"><div className="recordsHead"><div><h2>生成文件</h2><p>每条记录对应一次独立生成，包含当次业务数据和条款快照。</p></div><span className="fieldCount">{docs.length} 条记录</span></div>{docs.length===0?<div className="emptyState"><FileText size={28}/><b>暂无生成记录</b><span>填写合同字段并生成文件后，记录会显示在这里。</span></div>:docs.map(d=><div className="docRow" key={d.id}><div className="pdfIcon"><FileText size={21}/></div><div className="docDetails"><b>{d.templateName}</b><span>{d.pdfFileName}</span><small>{d.createdAt}</small></div><div className="docActions"><a href={d.downloadUrl}><Download size={15}/>下载 PDF</a><a href={d.docxUrl}><FileDown size={15}/>下载 DOCX</a></div></div>)}</section>}
   <footer><span>合同模板工作台 · Demo</span><span>React · Spring Boot · Apache POI · LibreOffice · MySQL</span></footer>
  </main>
 </div>
}
