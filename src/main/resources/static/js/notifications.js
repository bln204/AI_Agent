// Notification bell polling (decision #8: DB-persisted, client polls
// periodically — no WebSocket/SSE). Self-contained so it can run on any page
// that includes it (documents.html, document_view.html, projects.html,
// dashboard.html) without depending on another page's script being loaded.
(function () {
    const POLL_INTERVAL_MS = 20000;

    function getCsrfToken() {
        const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
        return match ? decodeURIComponent(match[1]) : null;
    }

    function csrfHeaders(extra) {
        const headers = extra || {};
        const token = getCsrfToken();
        if (token) headers['X-XSRF-TOKEN'] = token;
        return headers;
    }

    function timeAgo(isoString) {
        if (!isoString) return '';
        const diffMs = Date.now() - new Date(isoString).getTime();
        const minutes = Math.floor(diffMs / 60000);
        if (minutes < 1) return 'vừa xong';
        if (minutes < 60) return minutes + ' phút trước';
        const hours = Math.floor(minutes / 60);
        if (hours < 24) return hours + ' giờ trước';
        return Math.floor(hours / 24) + ' ngày trước';
    }

    async function refreshUnreadCount(badgeEl) {
        try {
            const res = await fetch('/api/notifications/unread-count');
            if (!res.ok) return;
            const data = await res.json();
            const count = data.count || 0;
            if (count > 0) {
                badgeEl.textContent = count > 99 ? '99+' : String(count);
                badgeEl.style.display = 'inline-block';
            } else {
                badgeEl.style.display = 'none';
            }
        } catch (e) {
            // Silent — this is a background convenience poll, never surface
            // a network hiccup to the user.
        }
    }

    async function loadList(listEl, badgeEl) {
        listEl.innerHTML = '<div class="notif-empty">Đang tải...</div>';
        try {
            const res = await fetch('/api/notifications?size=10');
            if (!res.ok) {
                listEl.innerHTML = '<div class="notif-empty">Không thể tải thông báo.</div>';
                return;
            }
            const page = await res.json();
            const items = page.content || [];
            if (items.length === 0) {
                listEl.innerHTML = '<div class="notif-empty">Chưa có thông báo nào.</div>';
                return;
            }
            listEl.innerHTML = '';
            items.forEach((n) => {
                const el = document.createElement('a');
                el.href = n.documentId ? ('/documents/' + n.documentId) : (n.projectId ? ('/projects/' + n.projectId) : '#');
                el.className = 'notif-item' + (n.read ? '' : ' unread');
                el.innerHTML = '<span>' + n.message + '</span><span class="notif-time">' + timeAgo(n.createdAt) + '</span>';
                el.addEventListener('click', () => {
                    if (!n.read) {
                        fetch('/api/notifications/' + n.id + '/read', { method: 'POST', headers: csrfHeaders() })
                            .then(() => refreshUnreadCount(badgeEl));
                    }
                });
                listEl.appendChild(el);
            });
        } catch (e) {
            listEl.innerHTML = '<div class="notif-empty">Không thể tải thông báo.</div>';
        }
    }

    function init() {
        const btn = document.getElementById('notifBellBtn');
        const dropdown = document.getElementById('notifDropdown');
        const badge = document.getElementById('notifBadge');
        const list = document.getElementById('notifList');
        if (!btn || !dropdown || !badge || !list) return;

        refreshUnreadCount(badge);
        setInterval(() => refreshUnreadCount(badge), POLL_INTERVAL_MS);

        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            const isOpen = dropdown.style.display === 'block';
            dropdown.style.display = isOpen ? 'none' : 'block';
            if (!isOpen) {
                loadList(list, badge);
            }
        });

        document.addEventListener('click', (e) => {
            if (!dropdown.contains(e.target) && e.target !== btn) {
                dropdown.style.display = 'none';
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
