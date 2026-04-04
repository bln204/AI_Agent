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

    // Nhấn Enter gửi tin nhắn (Shift+Enter xuống dòng)
    document.getElementById('messageInput')?.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });
    document.getElementById('messageInput')?.addEventListener('input', autoResizeTextarea);
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
            headers: { 'Content-Type': 'application/json' },
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
    const input = document.getElementById('messageInput');
    const content = input?.value.trim();
    if (!content || isTyping) return;

    // Tạo session nếu chưa có
    if (!currentSessionId) {
        await startNewChat();
    }

    // Ẩn welcome screen
    document.getElementById('welcomeScreen')?.remove();

    // Hiển thị tin nhắn user ngay
    appendMessageBubble('USER', content);
    input.value = '';
    autoResizeTextarea();

    // Typing indicator
    showTyping();
    isTyping = true;

    try {
        const res = await fetch(`/api/chat/sessions/${currentSessionId}/messages`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content })
        });
        const data = await res.json();

        removeTyping();
        appendMessageBubble('AI', data.aiMessage.content);

        // Cập nhật title session nếu là tin nhắn đầu
        await loadChatSessions();
    } catch (e) {
        removeTyping();
        appendMessageBubble('AI', '❌ Lỗi kết nối. Vui lòng thử lại.');
    } finally {
        isTyping = false;
        input.focus();
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
