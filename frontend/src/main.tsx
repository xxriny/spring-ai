import React, { useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './style.css'

type Scope = 'SHARED' | 'PRIVATE'
type Conversation = { id: string; title: string; scope: Scope }
type Source = { title: string; url: string; excerpt: string; documentId: string; page?: number }
type Message = { role: string; body: string; sources: Source[] }
type DocumentItem = { id: string; title: string; scope: Scope; sourceUrl: string | null; category: string; status: string }

const preview = import.meta.env.DEV && new URLSearchParams(window.location.search).has('preview')
const sampleConversation: Conversation = { id: 'preview', title: 'Spring AI의 대화 기억은 어떻게 동작하나요?', scope: 'SHARED' }
const sampleSources: Source[] = [
  { title: 'Chat Memory 공식 문서', url: 'https://docs.spring.io/spring-ai/reference/api/chat-memory.html', excerpt: '대화 ID를 기준으로 메시지를 저장하고 다음 요청에 필요한 이전 메시지를 전달합니다.', documentId: 'sample-1' },
  { title: 'ChatClient 가이드', url: 'https://docs.spring.io/spring-ai/reference/api/chatclient.html', excerpt: 'Advisor를 ChatClient에 연결해 대화 기억을 요청 처리 과정에 적용할 수 있습니다.', documentId: 'sample-2' }
]
const sampleMessages: Message[] = [
  { role: 'user', body: 'Spring AI의 대화 기억은 어떻게 동작하나요?', sources: [] },
  { role: 'assistant', body: 'Spring AI는 대화 ID를 기준으로 이전 메시지를 저장하고, 다음 질문에 필요한 맥락을 전달합니다. [1]\n\nChatClient에 MessageChatMemoryAdvisor를 연결하면 이 흐름을 대화 처리에 적용할 수 있습니다. [2]', sources: sampleSources }
]

function App() {
  const [auth, setAuth] = useState(preview ? 'preview' : '')
  const [name, setName] = useState(preview ? 'user' : '')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [scope, setScope] = useState<Scope>('SHARED')
  const [conversations, setConversations] = useState<Conversation[]>(preview ? [sampleConversation] : [])
  const [active, setActive] = useState<Conversation | null>(preview ? sampleConversation : null)
  const [messages, setMessages] = useState<Message[]>(preview ? sampleMessages : [])
  const [documents, setDocuments] = useState<DocumentItem[]>(preview ? sampleSources.map(source => ({ id: source.documentId, title: source.title, scope: 'SHARED', sourceUrl: source.url, category: 'Spring AI docs', status: 'READY' })) : [])
  const [question, setQuestion] = useState('')
  const [seed, setSeed] = useState('')
  const [category, setCategory] = useState('')
  const [busy, setBusy] = useState(false)
  const [libraryOpen, setLibraryOpen] = useState(false)

  async function api<T>(path: string, options: RequestInit = {}, credential = auth): Promise<T> {
    const response = await fetch('/api' + path, {
      ...options,
      headers: { Authorization: 'Basic ' + credential, ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }), ...options.headers }
    })
    if (!response.ok) {
      const detail = await response.json().catch(() => ({ error: `요청 실패 (${response.status})` }))
      throw new Error(detail.error || `요청 실패 (${response.status})`)
    }
    return response.status === 204 ? undefined as T : response.json()
  }

  async function signIn(event: React.FormEvent) {
    event.preventDefault()
    try {
      const credential = btoa(`${name}:${password}`)
      await api('/me', {}, credential)
      setAuth(credential)
      setPassword('')
      setError('')
    } catch (e) { setError((e as Error).message) }
  }

  useEffect(() => {
    if (!auth || preview) return
    api<Conversation[]>('/conversations').then(setConversations).catch(e => setError(e.message))
  }, [auth])
  useEffect(() => {
    if (!auth || preview) return
    api<DocumentItem[]>('/documents?scope=' + scope).then(setDocuments).catch(e => setError(e.message))
  }, [auth, scope])

  async function openConversation(conversation: Conversation) {
    if (preview) {
      setActive(conversation)
      setScope(conversation.scope)
      setMessages(conversation.id === sampleConversation.id ? sampleMessages : [])
      return
    }
    try {
      const list = await api<Message[]>(`/conversations/${conversation.id}/messages`)
      setActive(conversation)
      setScope(conversation.scope)
      setMessages(list)
      setError('')
    } catch (e) { setError((e as Error).message) }
  }

  function changeScope(next: Scope) {
    setScope(next)
    setActive(null)
    setMessages([])
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!question.trim() || busy) return
    setBusy(true)
    setError('')
    const text = question.trim()
    if (preview) {
      setMessages(previous => [...previous, { role: 'user', body: text, sources: [] }, { role: 'assistant', body: '화면 미리보기입니다. 실제 문서 검색과 답변은 백엔드를 실행한 뒤 사용할 수 있습니다.', sources: [] }])
      setQuestion('')
      setBusy(false)
      return
    }
    try {
      let conversation = active
      if (!conversation) {
        conversation = await api<Conversation>('/conversations', { method: 'POST', body: JSON.stringify({ scope }) })
        setActive(conversation)
        setConversations(previous => [conversation!, ...previous])
      }
      const answer = await api<{ answer: string; sources: Source[] }>(`/conversations/${conversation.id}/messages`, { method: 'POST', body: JSON.stringify({ text }) })
      setMessages(previous => [...previous, { role: 'user', body: text, sources: [] }, { role: 'assistant', body: answer.answer, sources: answer.sources }])
      setQuestion('')
      const list = await api<Conversation[]>('/conversations')
      setConversations(list)
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }

  async function upload(file: File) {
    if (preview) { setError(`${file.name}: 화면 미리보기에서는 업로드할 수 없습니다.`); return }
    if (!category.trim()) { setError('카테고리를 입력해 주세요.'); return }
    const body = new FormData()
    body.append('file', file)
    body.append('category', category.trim())
    setBusy(true)
    try {
      await api('/documents', { method: 'POST', body })
      setDocuments(await api<DocumentItem[]>('/documents?scope=' + scope))
      setError('문서가 등록되었습니다.')
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }

  async function crawl(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    try {
      const result = await api<{ indexed: number; skipped: number; errors: string[] }>('/admin/crawl', { method: 'POST', body: JSON.stringify({ url: seed, category: category.trim() }) })
      setDocuments(await api<DocumentItem[]>('/documents?scope=SHARED'))
      setError(`${result.indexed}개 문서 등록, ${result.skipped}개 건너뜀${result.errors.length ? `, 오류 ${result.errors.length}개` : ''}`)
      setSeed('')
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }

  async function deleteDocument(id: string) {
    if (preview) { setError('화면 미리보기에서는 문서를 삭제할 수 없습니다.'); return }
    if (!window.confirm('이 문서를 삭제할까요?')) return
    try {
      await api(`/documents/${id}`, { method: 'DELETE' })
      setDocuments(previous => previous.filter(document => document.id !== id))
    } catch (e) { setError((e as Error).message) }
  }

  if (!auth) return <main className="login-page"><form className="login-card" onSubmit={signIn}>
    <div className="brand-mark">✦</div><h1>Spring RAG</h1><p>내 문서와 공용 지식을 바탕으로 답을 찾아보세요.</p>
    <label>계정<input autoComplete="username" value={name} onChange={e => setName(e.target.value)} placeholder="admin 또는 user" required /></label>
    <label>비밀번호<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required /></label>
    {error && <div className="notice error" role="alert">{error}</div>}<button className="primary" type="submit">로그인</button>
  </form></main>

  const sources = [...messages].reverse().find(message => message.role === 'assistant')?.sources || []
  return <div className="app-shell">
    <aside className="sidebar"><div className="brand"><span className="brand-mark">✦</span> Spring RAG</div>
      <button className="new-chat" onClick={() => { setActive(null); setMessages([]) }}><span>＋</span> 새 대화</button>
      <button className="nav-button" onClick={() => setLibraryOpen(true)}>▤ <span>지식 문서</span><small>{documents.length}</small></button>
      <div className="sidebar-label">최근 대화</div>
      <div className="conversation-list">{conversations.map(item => <button key={item.id} className={'conversation ' + (active?.id === item.id ? 'selected' : '')} onClick={() => openConversation(item)}><span>{item.title}</span><small>{item.scope === 'SHARED' ? '공용 문서' : '내 문서'}</small></button>)}</div>
      <div className="sidebar-footer"><span className="avatar">{name.slice(0, 1).toUpperCase()}</span><span>{name}<small>{name === 'admin' ? '관리자' : '사용자'}</small></span><button title="로그아웃" onClick={() => { setAuth(''); setActive(null); setMessages([]) }}>↪</button></div>
    </aside>
    <main className="main-area"><header className="topbar"><div><span className="eyebrow">KNOWLEDGE CHAT {preview && '· 화면 미리보기'}</span><h1>{active?.title || '새 대화'}</h1></div><div className="scope-picker" aria-label="문서 검색 범위"><button className={scope === 'SHARED' ? 'active' : ''} onClick={() => changeScope('SHARED')}>공용 문서</button><button className={scope === 'PRIVATE' ? 'active' : ''} onClick={() => changeScope('PRIVATE')}>내 문서</button></div></header>
      <div className="workspace"><section className="chat-column">{messages.length === 0 ? <div className="welcome"><div className="welcome-symbol">✦</div><span className="eyebrow">ASK YOUR KNOWLEDGE</span><h2>무엇을 알고 싶으신가요?</h2><p>{scope === 'SHARED' ? '공용 문서' : '내 문서'}에서 근거를 찾아 답변합니다.<br/>질문을 입력해 대화를 시작해 보세요.</p><button onClick={() => setLibraryOpen(true)}>문서 살펴보기 →</button></div> : <div className="messages">{messages.map((message, index) => <article key={index} className={'message ' + message.role}><div className="message-icon">{message.role === 'user' ? name.slice(0, 1).toUpperCase() : '✦'}</div><div className="message-content"><div className="message-label">{message.role === 'user' ? '나' : 'Spring RAG'}</div><div className="message-body">{message.body}</div>{message.role === 'assistant' && message.sources.length > 0 && <div className="inline-sources">{message.sources.map((source, i) => <span key={i}>↗ {i + 1}. {source.title}</span>)}</div>}</div></article>)}</div>}
          <div className="composer-wrap">{error && <div className={'notice ' + (error.includes('등록') ? '' : 'error')} role="status">{error}</div>}<form className="composer" onSubmit={submit}><textarea aria-label="질문" placeholder="이어서 질문하기" value={question} onChange={e => setQuestion(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.form?.requestSubmit() } }} rows={1}/><button type="submit" disabled={busy || !question.trim()} aria-label="질문 보내기">➤</button></form><small className="composer-hint">답변은 선택한 문서의 근거를 바탕으로 생성됩니다.</small></div>
        </section><aside className="source-panel"><div className="source-heading"><span className="eyebrow">SOURCES</span><h2>참고 자료</h2><p>답변에 사용된 문서를 확인하세요.</p></div>{sources.length ? sources.map((source, i) => <div className="source-card" key={i}><span className="source-number">{i + 1}</span><div><strong>{source.title}</strong><p>{source.excerpt}</p><small>{source.page ? `페이지 ${source.page} · ` : ''}{source.url === 'upload' ? '업로드 문서' : source.url}</small>{source.url.startsWith('http') && <a href={source.url} target="_blank" rel="noopener noreferrer">원문 열기 ↗</a>}</div></div>) : <div className="empty-sources">▤<p>질문을 입력하면<br/>참고한 문서가 여기에 표시됩니다.</p></div>}</aside></div>
    </main>
    {libraryOpen && <div className="modal-backdrop" onClick={() => setLibraryOpen(false)}><section className="library" role="dialog" aria-modal="true" aria-label="지식 문서" onClick={e => e.stopPropagation()}><div className="library-head"><div><span className="eyebrow">KNOWLEDGE BASE</span><h2>지식 문서</h2></div><button onClick={() => setLibraryOpen(false)} aria-label="닫기">✕</button></div><p>{name === 'admin' ? '관리자 문서는 공용 지식으로 등록됩니다.' : '업로드한 문서는 내 문서로만 등록됩니다.'}</p><label className="category-field">카테고리<input value={category} onChange={e => setCategory(e.target.value)} maxLength={80} placeholder="예: Spring AI docs" required /></label><label className="upload-button">＋ 파일 업로드<input type="file" accept=".txt,.pdf,.doc,.docx,.html,.htm" disabled={busy} onChange={e => { const file = e.target.files?.[0]; if (file) upload(file); e.target.value = '' }}/></label>{name === 'admin' && <form className="crawl-form" onSubmit={crawl}><label>웹페이지 URL과 하위 페이지 수집</label><div><input type="url" placeholder="https://docs.example.com/guide/" value={seed} onChange={e => setSeed(e.target.value)} required/><button disabled={busy || !category.trim()}>수집</button></div></form>}<div className="library-list">{documents.map(doc => <div key={doc.id} className="library-item"><span>▤</span><div><strong>{doc.title}</strong><small>{doc.category} · {doc.sourceUrl || '업로드 문서'}</small></div><button onClick={() => deleteDocument(doc.id)} aria-label={`${doc.title} 삭제`}>삭제</button></div>)}{!documents.length && <p>아직 등록된 문서가 없습니다.</p>}</div></section></div>}
  </div>
}

createRoot(document.getElementById('root')!).render(<App />)
