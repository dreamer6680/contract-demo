import { useEffect, useState } from 'react'
import { FileText, Download, RefreshCw, ShieldCheck, CircleCheck, Clock3, Search } from 'lucide-react'

type Contract = { id: number; contractNo: string; sellerName: string; buyerName: string; totalAmount: number; status: string; createdAt: string; pdfPath?: string }
const initialForm = { contractNo: 'HT-2026-1026-001', sellerName: '', buyerName: '', totalAmount: '128000.00', signingDate: new Date().toISOString().slice(0, 10), deliveryAddress: '', remarks: '' }

export default function App() {
  const [form, setForm] = useState(initialForm)
  const [records, setRecords] = useState<Contract[]>([])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [query, setQuery] = useState('')
  const refresh = async () => {
    try { const r = await fetch('/api/contracts'); if (r.ok) setRecords(await r.json()) } catch { /* API may be offline during preview */ }
  }
  useEffect(() => { void refresh() }, [])
  const update = (key: keyof typeof initialForm, value: string) => setForm(old => ({ ...old, [key]: value }))
  const generate = async () => {
    setBusy(true); setNotice('')
    try {
      const response = await fetch('/api/contracts/generate', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...form, totalAmount: Number(form.totalAmount) }) })
      if (!response.ok) throw new Error(await response.text() || '生成失败，请检查后端服务')
      const blob = await response.blob(); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = form.contractNo + '.pdf'; a.click(); URL.revokeObjectURL(url)
      setNotice('合同文件已生成并开始下载。'); await refresh()
    } catch (e) { setNotice(e instanceof Error ? e.message : '请求失败，请确认后端已启动。') }
    finally { setBusy(false) }
  }
  const filtered = records.filter(r => [r.contractNo, r.sellerName, r.buyerName].join(' ').toLowerCase().includes(query.toLowerCase()))
  return <div className="appShell">
    <aside className="sidebar"><div className="brand"><div className="brandIcon"><FileText size={22}/></div><div><b>合同智造</b><small>CONTRACT STUDIO</small></div></div><div className="sideLabel">工作空间</div><button className="navItem active"><FileText size={17}/>合同生成 <span>当前</span></button><button className="navItem" onClick={() => document.getElementById('records')?.scrollIntoView({ behavior: 'smooth' })}><Clock3 size={17}/>生成记录</button><div className="sideBottom"><ShieldCheck size={16}/> 本地化文件处理<br/><small>模板数据仅用于演示</small></div></aside>
    <main className="mainArea"><header className="pageHeader"><div><div className="breadcrumb">工作空间 / 合同管理 / <b>合同生成</b></div><h1>合同生成工作台</h1><p>基于标准合同模板，快速生成规范化合同文件。</p></div><div className="headerTag"><span/> 原型环境</div></header>
      <div className="summaryCard"><div className="docBadge"><FileText size={25}/></div><div className="summaryText"><div className="eyebrow">当前模板</div><h2>煤炭销售合同</h2><p>Word 模板 · 自动填充 · PDF 导出</p></div><div className="summaryStatus"><CircleCheck size={16}/> 模板已就绪</div></div>
      <div className="callout"><ShieldCheck size={20}/><div><b>生成流程说明</b><span>填写合同主体与业务信息后，系统将填充 Word 模板并转换为 PDF。当前版本为功能原型，请核验生成文件内容。</span></div></div>
      <section className="formSection"><div className="formHeader"><div><h2>合同基本信息</h2><p>带 * 字段为必填项</p></div><div className="fieldCount">6 个信息字段</div></div>
        <div className="formGrid">
          <label className="field"><span>合同编号 *</span><input value={form.contractNo} onChange={e => update('contractNo', e.target.value)} placeholder="请输入合同编号"/></label>
          <label className="field"><span>签订日期 *</span><input type="date" value={form.signingDate} onChange={e => update('signingDate', e.target.value)}/></label>
          <label className="field"><span>卖方名称 *</span><input value={form.sellerName} onChange={e => update('sellerName', e.target.value)} placeholder="请输入卖方企业全称"/></label>
          <label className="field"><span>买方名称 *</span><input value={form.buyerName} onChange={e => update('buyerName', e.target.value)} placeholder="请输入买方企业全称"/></label>
          <label className="field"><span>合同总金额（元）*</span><input type="number" min="0" value={form.totalAmount} onChange={e => update('totalAmount', e.target.value)} placeholder="请输入合同金额"/></label>
          <label className="field"><span>交付地点</span><input value={form.deliveryAddress} onChange={e => update('deliveryAddress', e.target.value)} placeholder="请输入交付地点"/></label>
          <label className="field fullField"><span>补充说明</span><input value={form.remarks} onChange={e => update('remarks', e.target.value)} placeholder="可选，填写其他需要备注的信息"/></label>
        </div>
        {notice && <div className={notice.includes('失败') || notice.includes('检查') ? 'notice error' : 'notice'}>{notice}</div>}
        <div className="generatePanel"><div><h3>生成合同文件</h3><p>将按当前模板生成可下载的 PDF 文件</p><div className="pipeline"><span>信息校验</span>→<span>填充 DOCX</span>→<span>导出 PDF</span></div></div><button className="primaryBtn" disabled={busy || !form.contractNo || !form.sellerName || !form.buyerName || Number(form.totalAmount) <= 0} onClick={() => void generate()}>{busy ? <RefreshCw className="spin" size={17}/> : <Download size={17}/>} {busy ? '正在生成…' : '生成并下载 PDF'}</button></div>
      </section>
      <section className="recordsCard" id="records"><div className="recordsHead"><div><h2>最近生成记录</h2><p>查看本次环境中已生成的合同</p></div><div className="searchBox"><Search size={15}/><input value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索合同编号或企业"/></div></div>
        {filtered.length ? filtered.map(r => <div className="docRow" key={r.id}><div className="pdfIcon"><FileText size={20}/></div><div className="docDetails"><b>{r.contractNo}</b><span>{r.sellerName || '未填写卖方'} → {r.buyerName || '未填写买方'}</span></div><small>{r.createdAt || ''}</small><span className="recordStatus">{r.status || '已生成'}</span></div>) : <div className="emptyState"><FileText size={26}/><b>暂无生成记录</b><span>完成首份合同生成后，记录将显示在这里</span></div>}
      </section>
      <footer>合同智造 · 合同文件生成演示系统 <span>React · Spring Boot · Apache POI · LibreOffice · MySQL</span></footer>
    </main>
  </div>
}
