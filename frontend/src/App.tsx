import { useEffect, useState, type ReactNode } from 'react'
import { FileText, FileDown, FileCheck2, RefreshCw, Download, FileType2, ShieldCheck, CircleAlert, CheckCircle2, Plus, RotateCcw, Save, Copy } from 'lucide-react'

type RunStyle = {text?:string;bold?:boolean;italic?:boolean;underline?:string;fontFamily?:string;fontSize?:number;color?:string;businessField?:boolean}
type StructureNode = { type:string; text?:string; styleId?:string; styleName?:string; editable?:boolean; alignment?:string; indentationLeft?:number; indentationRight?:number; indentationFirstLine?:number; spacingBefore?:number; spacingAfter?:number; keepNext?:boolean; pageBreakBefore?:boolean; runs?:RunStyle[]; children?:StructureNode[]; rows?:StructureNode[]; cells?:StructureNode[] };
type TemplateStructure = { templateId:string; type:string; children:StructureNode[]; metadata?:{paragraphCount?:number;tableCount?:number} };
const collectEditableParagraphs = (nodes:StructureNode[]) => { const result:TemplateParagraph[]=[]; let index=0; const walk=(items:StructureNode[])=>items.forEach(node=>{ if(node.type==='paragraph'){ if(node.editable && (node.text||'').trim()) result.push({index,text:node.text||''}); index++; } else if(node.type==='table') (node.rows||[]).forEach(row=>(row.cells||[]).forEach(cell=>walk(cell.children||[]))); else walk(node.children||[]); }); walk(nodes); return result; };
function renderStructure(nodes:StructureNode[]):ReactNode { return nodes.map((node,i)=>{ if(node.type==='paragraph') return <div className="structureParagraph" key={'p'+i}><span className="structureKind">段落{node.styleId ? ' · '+node.styleId : ''}</span><span>{node.text||'（空段落）'}</span>{node.editable===false&&<em>只读/受保护</em>}</div>; if(node.type==='table') return <div className="structureTable" key={'t'+i}><div className="structureKind">表格 · {(node.rows||[]).length} 行</div><table><tbody>{(node.rows||[]).map((row,ri)=><tr key={ri}>{(row.cells||[]).map((cell,ci)=><td key={ci}>{renderStructure(cell.children||[])}</td>)}</tr>)}</tbody></table></div>; return <div key={node.type+i}>{renderStructure(node.children||node.cells||[])}</div>; }); }
type Field = { key: string; label: string; defaultValue: string }
type Clause = { id: string; title: string; description: string; content: string; anchors?: string[] }
type Doc = { id:number; templateName:string; pdfFileName:string; docxFileName:string; createdAt:string; downloadUrl:string; docxUrl:string }; type Template = {id:string;name:string;isDefault:boolean}; type TemplateParagraph = {index:number;text:string}
type NewSection = {id:string;title:string;content:string;beforeTitle:string}
const api = async (url:string, init?:RequestInit) => { const headers = init?.body instanceof FormData ? {} : {'Content-Type':'application/json'}; const r=await fetch('/api'+url,{headers:{...headers,...init?.headers},...init}); const j=await r.json().catch(()=>({error:'请求失败'})); if(!r.ok) throw new Error(j.message||j.error||'请求失败'); return j }
export default function App(){
 const [templateName,setTemplateName]=useState('煤炭买卖合同版本'), [saveBusy,setSaveBusy]=useState(false), [fields,setFields]=useState<Field[]>([]), [data,setData]=useState<Record<string,string>>({}), [docs,setDocs]=useState<Doc[]>([]), [clauses,setClauses]=useState<Clause[]>([]), [defaultClauses,setDefaultClauses]=useState<Clause[]>([]), [templates,setTemplates]=useState<Template[]>([]), [templateId,setTemplateId]=useState('default'), [previewUrl,setPreviewUrl]=useState(''), [previewBusy,setPreviewBusy]=useState(false), [templateParagraphs,setTemplateParagraphs]=useState<TemplateParagraph[]>([]), [templateStructure,setTemplateStructure]=useState<TemplateStructure|null>(null), [xmlOpen,setXmlOpen]=useState(false), [xmlContent,setXmlContent]=useState(''), [xmlBusy,setXmlBusy]=useState(false), [newSections,setNewSections]=useState<NewSection[]>([]), [sectionTitle,setSectionTitle]=useState('赔偿'), [sectionContent,setSectionContent]=useState('甲、乙双方应按照本合同约定承担相应赔偿责任。具体赔偿范围、计算方式及支付期限由双方协商确定。'), [sectionBefore,setSectionBefore]=useState('保密义务'), [insertAtTitle,setInsertAtTitle]=useState(''), [busy,setBusy]=useState(false), [notice,setNotice]=useState(''), [error,setError]=useState(false), [tab,setTab]=useState<'form'|'records'>('form')
 const refresh=async()=>{try{const [f,d,c,t]=await Promise.all([api('/contract-fields'),api('/documents'),api('/contract-clauses'),api('/templates')]);setFields(f);setDocs(d);setDefaultClauses(c);setClauses(old=>old.length?old:c);setTemplates(t);setData(old=>Object.keys(old).length?old:Object.fromEntries(f.map((x:Field)=>[x.key,String(x.defaultValue ?? '')])))}catch(e:any){setError(true);setNotice(e.message)}}
 useEffect(()=>{refresh()},[])
 useEffect(()=>{let active=true;setPreviewUrl('');setTemplateParagraphs([]);setTemplateStructure(null);api('/templates/'+templateId+'/structure').then((s:TemplateStructure)=>{if(active){setTemplateStructure(s);setTemplateParagraphs(collectEditableParagraphs(s.children||[]))}}).catch((e:any)=>{if(active){setTemplateStructure(null);setTemplateParagraphs([]);setError(true);setNotice('读取模板结构失败：'+e.message)}});return()=>{active=false}},[templateId])
 const addNewSection=()=>{const title=sectionTitle.trim();if(!title){setError(true);setNotice('请填写新增大类标题');return}if(newSections.some(s=>s.title===title)){setError(true);setNotice('新增大类标题重复，请修改标题');return}setNewSections(old=>[...old,{id:crypto.randomUUID(),title,content:sectionContent.trim(),beforeTitle:sectionBefore}]);setSectionTitle('');setSectionContent('');setInsertAtTitle('');setNotice('新增大类已加入待生成列表；生成时会写入 DOCX 并尝试沿用 Word 标题编号与样式。');setError(false)}
 const removeNewSection=(id:string)=>setNewSections(old=>old.filter(s=>s.id!==id))
 const updateClause=(id:string,content:string)=>setClauses(old=>old.map(c=>c.id===id?{...c,content}:c))
 let paperParagraphIndex=0
 const renderPaperNodes=(nodes:StructureNode[]):ReactNode=>nodes.map((node,i)=>{
  if(node.type==='paragraph'){
   const index=paperParagraphIndex++
   const editable=templateParagraphs.find(p=>p.index===index)
   const text=editable?.text ?? rendered(node.text||'')
   const firstRun=node.runs?.[0]
   const paragraphStyle:Record<string,string|number>={}
   if(node.alignment) paragraphStyle.textAlign=({CENTER:'center',RIGHT:'right',BOTH:'justify',DISTRIBUTE:'justify'} as Record<string,string>)[node.alignment]||'left'
   if(typeof node.indentationLeft==='number'&&node.indentationLeft>0) paragraphStyle.marginLeft=(node.indentationLeft/20)+'pt'
   if(typeof node.indentationRight==='number'&&node.indentationRight>0) paragraphStyle.marginRight=(node.indentationRight/20)+'pt'
   if(typeof node.indentationFirstLine==='number'&&node.indentationFirstLine!==0) paragraphStyle.textIndent=(node.indentationFirstLine/20)+'pt'
   if(typeof node.spacingBefore==='number'&&node.spacingBefore>0) paragraphStyle.marginTop=(node.spacingBefore/20)+'pt'
   if(typeof node.spacingAfter==='number'&&node.spacingAfter>0) paragraphStyle.marginBottom=(node.spacingAfter/20)+'pt'
   if(firstRun?.fontFamily) paragraphStyle.fontFamily=firstRun.fontFamily
   if(firstRun?.fontSize&&firstRun.fontSize>0) paragraphStyle.fontSize=firstRun.fontSize+'pt'
   if(firstRun?.bold) paragraphStyle.fontWeight=700
   if(firstRun?.italic) paragraphStyle.fontStyle='italic'
   if(firstRun?.color) paragraphStyle.color='#'+firstRun.color
   const richRuns=(node.runs||[]).map((run,ri)=><span key={ri} style={{fontFamily:run.fontFamily||undefined,fontSize:run.fontSize&&run.fontSize>0?run.fontSize+'pt':undefined,fontWeight:run.bold?700:undefined,fontStyle:run.italic?'italic':undefined,textDecoration:run.underline&&run.underline!=='none'?'underline':undefined,color:run.color?'#'+run.color:undefined}}>{rendered(run.text||'')}</span>)
   const headingCandidate=/^\\s*(?:第?[一二三四五六七八九十百0-9]+[、.． ]|[一二三四五六七八九十]+、)/.test((node.text||'').trim()) || /heading|title/i.test(node.styleId||'') || /标题/.test(node.styleName||'')
   const insertControls=headingCandidate ? <div className="inlineInsertWrap" key={'insert-'+index}>
    <button className="inlineInsertButton" onClick={()=>{setSectionBefore(node.text||'');setInsertAtTitle(insertAtTitle===(node.text||'')?'':(node.text||''))}}><Plus size={13}/>在本章节前插入大类</button>
    {insertAtTitle===(node.text||'')&&<div className="inlineInsertEditor">
      <div className="inlineInsertEditorTitle">在“{(node.text||'').slice(0,50)}”之前插入新章节</div>
      <label>大类标题<input value={sectionTitle} onChange={e=>setSectionTitle(e.target.value)} placeholder="例如：赔偿"/></label>
      <label>正文内容<textarea value={sectionContent} onChange={e=>setSectionContent(e.target.value)} rows={3} placeholder="输入章节正文"/></label>
      <div className="inlineInsertActions"><button className="secondaryBtn" onClick={()=>setInsertAtTitle('')}>取消</button><button className="primaryBtn" onClick={addNewSection}><Plus size={14}/>插入章节</button></div>
    </div>}
   </div> : null
   return <div className={['simPdfParagraph',editable?'editable':'protected',node.styleId||''].join(' ')} style={paragraphStyle} key={'paper-p-'+index}>
    {editable?<div className="simPdfEditableText" role="textbox" aria-label={'编辑合同段落 '+(index+1)} aria-multiline="true" contentEditable suppressContentEditableWarning onBlur={e=>updateTemplateParagraph(index,e.currentTarget.innerText.replace(/\\n$/,''))}>{editable.text===node.text?richRuns:editable.text||' '}</div>:<div className="simPdfText">{richRuns.length?richRuns:(text||' ')}</div>}
    {node.editable===false&&node.text?.trim()&&<span className="simPdfLock">业务字段 / 受保护</span>}
   </div>
  }
  if(node.type==='table') return <div className="simPdfTable" key={'paper-t-'+i}><table><tbody>{(node.rows||[]).map((row,ri)=><tr key={ri}>{(row.cells||[]).map((cell,ci)=><td key={ci}>{renderPaperNodes(cell.children||[])}</td>)}</tr>)}</tbody></table></div>
  return <div key={'paper-n-'+i}>{renderPaperNodes(node.children||node.cells||[])}</div>
 })
 const addClauseLine=(id:string)=>setClauses(old=>old.map(c=>c.id===id?{...c,content:(c.content.trimEnd()?c.content.trimEnd()+'\n':'')+'新增条款：请填写条款内容。'}:c))
 const resetClauses=()=>{setClauses(defaultClauses.map(c=>({...c})));setNotice('本次编辑已恢复为默认条款；不会修改公共模板。');setError(false)}
 const rendered=(text:string)=>text.replace(/\{<\$\.([A-Za-z0-9_.]+)>}/g,(_,key:string)=>data[key]??'')
 const payload=()=>JSON.stringify({templateId,data,clauses:clauses.map(({id,content})=>({id,content})),paragraphEdits:templateParagraphs.map(({index,text})=>({index,text})),newSections}); const generate=async()=>{setBusy(true);setNotice('');try{const r=await api('/documents',{method:'POST',body:payload()});setNotice(r.message);setError(false);const d=await api('/documents');setDocs(d);window.open(r.downloadUrl,'_blank','noopener,noreferrer')}catch(e:any){setError(true);setNotice(e.message)}finally{setBusy(false)}}; const updateTemplateParagraph=(index:number,text:string)=>setTemplateParagraphs(old=>old.map(p=>p.index===index?{...p,text}:p)); const updatePreview=async()=>{setPreviewBusy(true);setNotice('');try{const r=await api('/preview',{method:'POST',body:payload()});setPreviewUrl(r.url+'?v='+Date.now());setNotice(r.message);setError(false)}catch(e:any){setError(true);setNotice(e.message)}finally{setPreviewBusy(false)}}; const saveTemplateVersion=async()=>{setSaveBusy(true);setNotice('');try{const r=await api('/templates/save',{method:'POST',body:JSON.stringify({templateId,name:templateName,data,clauses:clauses.map(({id,content})=>({id,content})),paragraphEdits:templateParagraphs.map(({index,text})=>({index,text})),newSections})});const list=await api('/templates');setTemplates(list);setTemplateId(r.id);setPreviewUrl('');setNotice(r.message);setError(false)}catch(e:any){setError(true);setNotice(e.message)}finally{setSaveBusy(false)}}
 return <div className="appShell">
  <aside className="sidebar"><div className="brand"><div className="brandIcon"><FileText size={22}/></div><div><b>合同模板工作台</b><small>DOCX → PDF WORKFLOW</small></div></div><div className="sideLabel">工作空间</div><button className={tab==='form'?'navItem active':'navItem'} onClick={()=>setTab('form')}><FileType2 size={17}/>合同填写与编辑</button><button className={tab==='records'?'navItem active':'navItem'} onClick={()=>setTab('records')}><FileCheck2 size={17}/>生成记录 <span>{docs.length}</span></button><div className="sideBottom"><div className="safeTag"><ShieldCheck size={16}/>本次编辑独立生效</div><p>保存会创建独立模板版本，不覆盖公共模板或已有版本。</p></div></aside>
  <main className="mainArea"><header className="pageHeader"><div><div className="breadcrumb">合同模板 / {tab==='form'?'填写、编辑与生成':'生成记录'}</div><h1>{tab==='form'?'煤炭买卖合同':'生成记录'}</h1><p>{tab==='form'?'从已保存的模板版本中选择，编辑后可保存为新版本；业务字段保持只读。':'查看生成的 PDF 和可编辑 DOCX 文件。'}</p></div><button className="iconBtn" onClick={refresh} title="刷新数据"><RefreshCw size={17}/></button></header>
   {notice&&<div className={error?'notice error':'notice'}>{error?<CircleAlert size={17}/>:<CheckCircle2 size={17}/>}<span>{notice}</span><button onClick={()=>setNotice('')}>×</button></div>}
   {tab==='form'?<>
    <section className="summaryCard"><div className="docBadge"><FileText size={25}/></div><div className="summaryText"><div className="eyebrow">SOURCE DOCX TEMPLATE</div><h2>选择合同模板</h2><p>生成和 PDF 预览均使用当前选中的原始 DOCX 文件。</p><div className="templateControls"><select aria-label="选择模板" value={templateId} onChange={e=>{setTemplateId(e.target.value);setPreviewUrl('')}}>{templates.map(t=><option key={t.id} value={t.id}>{t.name}{t.isDefault?'（公共模板）':'（自定义模板）'}</option>)}</select><input aria-label="模板版本名称" className="templateNameInput" value={templateName} onChange={e=>setTemplateName(e.target.value)} placeholder="输入保存的模板名称"/><button className="secondaryBtn" disabled={saveBusy} onClick={saveTemplateVersion}>{saveBusy?<RefreshCw className="spin" size={14}/>:<Save size={14}/>}保存模板版本</button><button className="secondaryBtn" disabled={saveBusy} onClick={()=>setTemplateName((templates.find(t=>t.id===templateId)?.name||'合同模板')+' - 新版本')}><Copy size={14}/>另存为新版本</button></div></div><div className="summaryStatus"><span className="greenDot"/>模板独立保存</div></section>
    <div className="callout"><b>编辑规则</b><span>业务数据由后端返回，只读展示并自动渲染到合同中；仅固定条款可在当前合同中修改或追加。不会修改 DOCX 模板，也不会影响其他合同。</span></div>
    <div className="formHeader clauseHeader"><div><h2>合同条款编辑</h2><p>修改条款后，点击“更新 PDF 预览”查看实际分页；预览页数与最终生成文件保持一致。</p></div><div className="previewActions"><button className="secondaryBtn" onClick={resetClauses}><RotateCcw size={14}/>恢复默认条款</button><button className="primaryBtn" disabled={previewBusy} onClick={updatePreview}>{previewBusy?<RefreshCw className="spin" size={14}/>:<FileText size={14}/>}更新 PDF 预览</button></div></div>
    <div className="clauseNotice"><ShieldCheck size={17}/><span><b>业务字段只读</b> · 下方合同信息来自后端接口，不提供手动修改入口；固定条款的编辑仅对本次生成生效。</span></div>
    {newSections.length>0&&<section className="newSectionsPanel">
     <div className="newSectionsHeading"><div><h2>待插入章节</h2><p>章节将在所选标题之前插入；点击文档中各章节前的“在本章节前插入大类”可直接定位插入位置。</p></div><span className="newSectionCount">{newSections.length} 个待插入</span></div>
     <div className="newSectionList">{newSections.map(s=><div className="newSectionItem" key={s.id}><div><b>{s.title}</b><p>{s.content||'（正文为空）'}</p><small>插入位置：{s.beforeTitle||'文档末尾'}</small></div><button className="secondaryBtn" onClick={()=>removeNewSection(s.id)}>移除</button></div>)}</div>
    </section>}
    <section className="simPdfWorkspace">
     <div className="simPdfToolbar"><div><FileText size={16}/><b>合同页面 · 直接编辑模式</b><span>前端版式模拟</span></div><div className="simPdfToolbarHint">点击正文即可编辑 · 业务字段受保护 <button className="xmlToggleBtn" onClick={async()=>{if(xmlOpen){setXmlOpen(false);return}setXmlOpen(true);if(!xmlContent){setXmlBusy(true);try{const result=await api('/templates/'+templateId+'/xml');setXmlContent(result.xml||'')}catch(e:any){setXmlContent('读取 XML 失败：'+e.message)}finally{setXmlBusy(false)}}}}> {xmlOpen?'收起 XML':'查看原始 XML'} </button></div></div>
     {xmlOpen&&<section className="docXmlPanel"><div className="docXmlHeader"><div><b>DOCX 原始 XML</b><span>当前显示 Word 文档主体 document.xml，用于检查段落、样式和 run 结构。</span></div><button className="secondaryBtn" onClick={async()=>{try{await navigator.clipboard.writeText(xmlContent);setNotice('XML 已复制到剪贴板');setError(false)}catch{setNotice('复制失败，请手动选择 XML 文本复制');setError(true)}}} disabled={!xmlContent||xmlBusy}><Copy size={14}/>复制 XML</button></div>{xmlBusy?<p className="paragraphLoading">正在读取 XML…</p>:<pre className="docXmlCode">{xmlContent||'暂无 XML 内容'}</pre>}</section>}<div className="simPdfCanvas">
      {!templateStructure?<p className="paragraphLoading">正在读取 DOCX 结构…</p>:<article className="simPdfPage">
       <div className="simPdfPageHead"><span>合同模板预览</span><span>编辑模式 · 非最终 PDF</span></div>
       <h2 className="simPdfTitle">煤炭买卖合同</h2>
       <div className="simPdfContent">{renderPaperNodes(templateStructure.children||[])}</div>
       <div className="simPdfPageFoot">前端模拟页面 · 最终分页和排版以生成的 PDF 为准</div>
      </article>}
     </div>
     <div className="simPdfFooter"><span>已识别 {templateStructure?.metadata?.paragraphCount??0} 个段落、{templateStructure?.metadata?.tableCount??0} 张表格</span><span>编辑内容会同步用于 PDF 预览与合同生成</span><button className="secondaryBtn" disabled={previewBusy} onClick={updatePreview}>{previewBusy?<RefreshCw className="spin" size={14}/>:<FileText size={14}/>}生成真实 PDF 预览</button></div>
    </section>
    <section className="generatePanel"><div><h3>生成合同文件</h3><p>系统将使用当前选中的模板，应用业务字段和本次条款修改，生成 DOCX 并转换为 PDF。</p><div className="pipeline"><span>填写业务数据</span><i>→</i><span>应用本次条款</span><i>→</i><span>替换占位符</span><i>→</i><span>转换 PDF</span></div></div><button className="primaryBtn" disabled={busy||fields.length===0} onClick={generate}>{busy?<RefreshCw className="spin" size={17}/>:<Download size={17}/>}{busy?'正在生成…':'生成 PDF 合同'}</button></section>
   </>:<section className="recordsCard"><div className="recordsHead"><div><h2>生成文件</h2><p>每条记录对应一次独立生成，包含当次业务数据和条款快照。</p></div><span className="fieldCount">{docs.length} 条记录</span></div>{docs.length===0?<div className="emptyState"><FileText size={28}/><b>暂无生成记录</b><span>填写合同字段并生成文件后，记录会显示在这里。</span></div>:docs.map(d=><div className="docRow" key={d.id}><div className="pdfIcon"><FileText size={21}/></div><div className="docDetails"><b>{d.templateName}</b><span>{d.pdfFileName}</span><small>{d.createdAt}</small></div><div className="docActions"><a href={d.downloadUrl}><Download size={15}/>下载 PDF</a><a href={d.docxUrl}><FileDown size={15}/>下载 DOCX</a></div></div>)}</section>}
   <footer><span>合同模板工作台 · Demo</span><span>React · Spring Boot · Apache POI · LibreOffice · MySQL</span></footer>
  </main>
 </div>
}
