// ═══════════════════════════════════════════════════════════════
// DASHBOARD — ChatGPT Style JavaScript
// ═══════════════════════════════════════════════════════════════

let currentSessionId = null;
let isTyping = false;

document.addEventListener('DOMContentLoaded', () => {
    loadChatSessions();
    setupEventListeners();
    autoResizeTextarea();
});

// ═══ Setup ═══
function setupEventListeners() {
    // Toggle sidebar (mobile)
    document.getElementById('toggleSidebar')?.addEventListener('click', () => {
        document.getElementById('sidebar').classList.toggle('open');
    });

    // New chat
    document.getElementById('newChatBtn')?.addEventListener('click', startNewChat);

    // Unbind possible existing listeners before binding to prevent duplicates
    const messageInput = document.getElementById('messageInput');
    if (messageInput) {
        // We use a named function for the listener so we could remove it if needed, 
        // but since setupEventListeners is only called once on DOMContentLoaded, we'll just ensure it's clean.
        messageInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendMessage();
            }
        });
        messageInput.addEventListener('input', autoResizeTextarea);
    }
}

function autoResizeTextarea() {
    const textarea = document.getElementById('messageInput');
    if (!textarea) return;
    textarea.style.height = 'auto';
    textarea.style.height = Math.min(textarea.scrollHeight, 150) + 'px';
}

// ═══ Chat Sessions ═══
async function loadChatSessions() {
    try {
        const res = await fetch('/api/chat/sessions');
        if (!res.ok) return;
        const sessions = await res.json();
        renderSessionList(sessions);
    } catch (e) {
        console.error('Lỗi load sessions:', e);
    }
}

function renderSessionList(sessions) {
    const container = document.getElementById('chatHistory');
    if (!sessions || sessions.length === 0) {
        container.innerHTML = '<div class="history-empty">Chưa có cuộc trò chuyện nào</div>';
        return;
    }
    container.innerHTML = sessions.map(s => `
        <div class="history-item ${s.id === currentSessionId ? 'active' : ''}"
             data-session-id="${s.id}"
             onclick="loadSession(${s.id})">
            <span title="${escapeHtml(s.title)}">${escapeHtml(s.title)}</span>
            <button class="delete-btn" onclick="deleteSession(event, ${s.id})" title="Xóa">✕</button>
        </div>
    `).join('');
}

async function loadSession(sessionId) {
    currentSessionId = sessionId;
    document.getElementById('welcomeScreen')?.remove();

    // Highlight trong sidebar
    document.querySelectorAll('.history-item').forEach(el => el.classList.remove('active'));
    document.querySelector(`[data-session-id="${sessionId}"]`)?.classList.add('active');

    try {
        const [sessionRes, msgRes] = await Promise.all([
            fetch(`/api/chat/sessions`),
            fetch(`/api/chat/sessions/${sessionId}/messages`)
        ]);
        const sessions = await sessionRes.json();
        const messages = await msgRes.json();

        const session = sessions.find(s => s.id === sessionId);
        if (session) {
            document.getElementById('chatTitle').textContent = session.title;
        }

        renderMessages(messages);
    } catch (e) {
        console.error('Lỗi load session:', e);
    }
}

function renderMessages(messages) {
    const container = document.getElementById('chatMessages');
    container.innerHTML = '';
    messages.forEach(msg => appendMessageBubble(msg.role, msg.content, false));
    scrollToBottom();
}

// ═══ New Chat ═══
async function startNewChat() {
    try {
        const res = await fetch('/api/chat/sessions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json; charset=UTF-8' },
            body: JSON.stringify({ title: 'Cuộc trò chuyện mới' })
        });
        const session = await res.json();
        currentSessionId = session.id;

        // Reset UI
        const container = document.getElementById('chatMessages');
        container.innerHTML = `
            <div class="welcome-screen" id="welcomeScreen">
                <div class="welcome-icon">🤖</div>
                <h2>Cuộc trò chuyện mới</h2>
                <p>Nhập câu hỏi của bạn bên dưới để bắt đầu.</p>
            </div>`;
        document.getElementById('chatTitle').textContent = 'Cuộc trò chuyện mới';

        await loadChatSessions();
        document.getElementById('messageInput')?.focus();
    } catch (e) {
        console.error('Lỗi tạo session:', e);
    }
}

// ═══ Send Message ═══
async function sendMessage() {
    // 1. ATOMIC GUARD: Check isTyping immediately
    if (isTyping) {
        console.warn("[UI-GUARD] Blocking double submit.");
        return;
    }

    const input = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendBtn'); // Optional send button
    const content = input?.value.trim();
    if (!content) return;

    // 2. LOCK UI: Disable interactions immediately
    isTyping = true;
    if (sendBtn) sendBtn.disabled = true;
    if (input) input.disabled = true;

    // 3. Create Session if needed
    if (!currentSessionId) {
        try {
            await startNewChat();
        } catch (e) {
            console.error('Lỗi tạo session:', e);
            isTyping = false;
            if (sendBtn) sendBtn.disabled = false;
            if (input) input.disabled = false;
            return;
        }
    }

    // 4. Generate SINGLE idempotency key for this user action
    const idempotencyKey = window.crypto?.randomUUID ? window.crypto.randomUUID() : 
                          (Date.now().toString(36) + Math.random().toString(36).substring(2));

    // 5. Update UI
    document.getElementById('welcomeScreen')?.remove();
    appendMessageBubble('USER', content);
    if (input) {
        input.value = '';
        autoResizeTextarea();
    }
    showTyping();

    try {
        const res = await fetch(`/api/chat/sessions/${currentSessionId}/messages`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json; charset=UTF-8' },
            body: JSON.stringify({ content, idempotencyKey })
        });
        
        if (res.status === 409) {
            removeTyping();
            appendMessageBubble('AI', '⚠️ Hệ thống đang xử lý một câu hỏi khác trong cuộc trò chuyện này. Vui lòng đợi.');
            return;
        }

        const data = await res.json();
        removeTyping();

        // Validate contract before render
        if (!data || typeof data.status !== 'string') {
            console.error('Invalid API response contract:', data);
            appendMessageBubble('AI', '❌ Lỗi hệ thống: Phản hồi không hợp lệ.');
            return;
        }

        // Render by status
        if (data.status === 'COMPLETED') {
            if (!data.content || !data.content.trim()) {
                appendMessageBubble('AI', '❌ Phản hồi từ hệ thống bị rỗng.');
                return;
            }
            appendMessageBubble('AI', data.content);
        } else if (data.status === 'FAILED' || data.status === 'RETRYABLE_ERROR') {
            const errorMsg = resolveErrorMessage(data.errorCode, data.retryable);
            appendMessageBubble('AI', '❌ ' + errorMsg);
        } else {
            appendMessageBubble('AI', '❌ Trạng thái phản hồi không hợp lệ: ' + data.status);
        }

        await loadChatSessions();
    } catch (e) {
        removeTyping();
        appendMessageBubble('AI', '❌ Lỗi kết nối. Vui lòng thử lại.');
        console.error('Send error:', e);
    } finally {
        // 6. UNLOCK UI
        isTyping = false;
        if (sendBtn) sendBtn.disabled = false;
        if (input) {
            input.disabled = false;
            input.focus();
        }
    }
}

// Quick message từ welcome chips
function sendQuickMessage(text) {
    const input = document.getElementById('messageInput');
    if (input) {
        input.value = text;
        sendMessage();
    }
}

// Expose to HTML
window.sendMessage = sendMessage;
window.sendQuickMessage = sendQuickMessage;

// ═══ Delete Session ═══
async function deleteSession(event, sessionId) {
    event.stopPropagation();
    if (!confirm('Xóa cuộc trò chuyện này?')) return;

    try {
        const res = await fetch(`/api/chat/sessions/${sessionId}`, { method: 'DELETE' });
        
        if (!res.ok) {
            const errorData = await res.json().catch(() => ({}));
            alert('Lỗi: ' + (errorData.message || 'Không thể xóa cuộc trò chuyện.'));
            return;
        }

        // Nếu session đang mở bị xóa, reset vùng chat
        if (currentSessionId === sessionId) {
            currentSessionId = null;
            const container = document.getElementById('chatMessages');
            if (container) {
                container.innerHTML = `
                    <div class="welcome-screen" id="welcomeScreen">
                        <div class="welcome-icon">🤖</div>
                        <h2>Xin chào!</h2>
                        <p>Hãy bắt đầu một cuộc trò chuyện mới.</p>
                    </div>`;
            }
            const titleEl = document.getElementById('chatTitle');
            if (titleEl) titleEl.textContent = 'Cuộc trò chuyện mới';
        }
        
        // Cập nhật lại danh sách sidebar
        await loadChatSessions();
    } catch (e) {
        console.error('Lỗi xóa session:', e);
        alert('Đã có lỗi xảy ra khi kết nối đến máy chủ.');
    }
}

// ═══ UI Helpers ═══
function appendMessageBubble(role, content, animate = true) {
    const container = document.getElementById('chatMessages');
    const isUser = role === 'USER';
    const div = document.createElement('div');
    div.className = `message-group ${isUser ? 'user' : 'ai'}`;
    div.innerHTML = `
        <div class="msg-avatar">${isUser ? (CURRENT_USER?.username?.[0]?.toUpperCase() || 'U') : '🤖'}</div>
        <div class="msg-bubble">${formatMessage(content)}</div>
    `;
    if (animate) {
        div.style.opacity = '0';
        div.style.transform = 'translateY(10px)';
        div.style.transition = 'opacity 0.3s, transform 0.3s';
        container.appendChild(div);
        requestAnimationFrame(() => {
            div.style.opacity = '1';
            div.style.transform = 'translateY(0)';
        });
    } else {
        container.appendChild(div);
    }
    scrollToBottom();
}

function showTyping() {
    const container = document.getElementById('chatMessages');
    const div = document.createElement('div');
    div.className = 'message-group ai';
    div.id = 'typingIndicator';
    div.innerHTML = `
        <div class="msg-avatar">🤖</div>
        <div class="msg-bubble typing-indicator">
            <span class="dot"></span>
            <span class="dot"></span>
            <span class="dot"></span>
        </div>`;
    container.appendChild(div);
    scrollToBottom();
}

function removeTyping() {
    document.getElementById('typingIndicator')?.remove();
}

function scrollToBottom() {
    const container = document.getElementById('chatMessages');
    if (container) container.scrollTop = container.scrollHeight;
}
function formatMessage(text) {
    // Basic markdown-like formatting
    return escapeHtml(text)
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
        .replace(/\*(.*?)\*/g, '<em>$1</em>')
        .replace(/`(.*?)`/g, '<code>$1</code>')
        .replace(/\n/g, '<br>');
}

function resolveErrorMessage(errorCode, retryable) {
    switch (errorCode) {
        case 'AI_RATE_LIMIT':
            return 'Hệ thống đang bận, vui lòng thử lại sau.';
        case 'AI_QUOTA_EXCEEDED':
            return 'Hệ thống đã hết quota xử lý.';
        case 'EMPTY_COMPLETED_CONTENT':
            return 'Phản hồi hệ thống không hợp lệ.';
        case 'SESSION_BUSY':
            return 'Hệ thống đang xử lý một câu hỏi khác trong cuộc trò chuyện này. Vui lòng đợi.';
        case 'MISSING_IDEMPOTENCY_KEY':
            return 'Yêu cầu không hợp lệ: thiếu khóa định danh.';
        default:
            return retryable
                ? 'Tạm thời không xử lý được, vui lòng thử lại.'
                : 'Đã xảy ra lỗi hệ thống.';
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return str.toString()
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

// Handle KeyDown from HTML
function handleKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage();
    }
}
window.handleKeyDown = handleKeyDown;
