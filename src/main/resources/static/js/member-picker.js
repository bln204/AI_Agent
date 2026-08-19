// Ô tìm + chọn nhiều nhân viên (kèm avatar thật/chữ cái đầu, phòng ban + role
// thay cho @username) dùng chung cho form Tạo dự án và form Thêm thành viên ở
// trang chi tiết dự án. Mỗi trang set window.ALL_USERS (mảng {id, username,
// avatarUrl, department, role}) trước khi include file này; initMemberPickers()
// tự tìm mọi phần tử .member-picker trên trang và gắn hành vi vào.
(function () {
    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str == null ? '' : String(str);
        return div.innerHTML;
    }

    function avatarHtml(user) {
        if (user.avatarUrl) {
            return '<img src="' + escapeHtml(user.avatarUrl) + '" alt="" class="w-10 h-10 rounded-full object-cover flex-shrink-0">';
        }
        const initial = (user.username || '?').trim().charAt(0).toUpperCase();
        return '<div class="w-10 h-10 rounded-full bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center text-white font-bold text-sm flex-shrink-0">' + escapeHtml(initial) + '</div>';
    }

    function subLabel(user) {
        const parts = [user.department, user.role].filter(Boolean);
        return parts.length ? parts.join(' • ') : '—';
    }

    function initPicker(root) {
        const users = window.ALL_USERS || [];
        const excludeIds = (root.dataset.excludeIds || '')
            .split(',').map(s => s.trim()).filter(Boolean).map(Number);
        const excludeSet = new Set(excludeIds);
        const memberField = root.dataset.fieldMember || 'memberIds';
        const leaderField = root.dataset.fieldLeader || 'leaderId';

        const input = root.querySelector('.mp-input');
        const suggestionsEl = root.querySelector('.mp-suggestions');
        const selectedEl = root.querySelector('.mp-selected');
        const hiddenEl = root.querySelector('.mp-hidden-inputs');

        // Map giữ thứ tự chọn -> {user, leader:boolean}
        const selected = new Map();

        function availableUsers(query) {
            const q = (query || '').trim().toLowerCase();
            return users.filter(u => !excludeSet.has(u.id) && !selected.has(u.id)
                && (!q || (u.username || '').toLowerCase().includes(q)));
        }

        function renderSuggestions(query) {
            const list = availableUsers(query).slice(0, 30);
            if (list.length === 0) {
                suggestionsEl.innerHTML = '<div class="px-4 py-3 text-sm text-slate-400">Không tìm thấy nhân viên phù hợp.</div>';
            } else {
                suggestionsEl.innerHTML = list.map(u => (
                    '<div class="mp-suggestion flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50 cursor-pointer border-b border-slate-50 last:border-0" data-id="' + u.id + '">'
                    + avatarHtml(u)
                    + '<div class="min-w-0">'
                    + '<div class="font-bold text-slate-900 text-sm truncate">' + escapeHtml(u.username) + '</div>'
                    + '<div class="text-xs text-slate-400 truncate">' + escapeHtml(subLabel(u)) + '</div>'
                    + '</div></div>'
                )).join('');
            }
            suggestionsEl.classList.remove('hidden');
        }

        function hideSuggestions() {
            suggestionsEl.classList.add('hidden');
        }

        function renderSelected() {
            if (selected.size === 0) {
                selectedEl.innerHTML = '<p class="text-xs text-slate-400 italic">Chưa chọn thành viên nào.</p>';
            } else {
                selectedEl.innerHTML = Array.from(selected.values()).map(entry => {
                    const u = entry.user;
                    return '<div class="flex items-center justify-between gap-3 px-3 py-2 bg-slate-50 border border-slate-100 rounded-xl" data-selected-id="' + u.id + '">'
                        + '<div class="flex items-center gap-3 min-w-0">'
                        + avatarHtml(u)
                        + '<div class="min-w-0">'
                        + '<div class="font-bold text-slate-900 text-sm truncate">' + escapeHtml(u.username) + '</div>'
                        + '<div class="text-xs text-slate-400 truncate">' + escapeHtml(subLabel(u)) + '</div>'
                        + '</div></div>'
                        + '<div class="flex items-center gap-3 flex-shrink-0">'
                        + '<label class="flex items-center gap-1.5 text-xs font-bold text-indigo-600 cursor-pointer select-none">'
                        + '<input type="checkbox" class="mp-leader-toggle rounded text-indigo-600 focus:ring-indigo-500" ' + (entry.leader ? 'checked' : '') + '>'
                        + 'Leader</label>'
                        + '<button type="button" class="mp-remove text-slate-400 hover:text-red-600 font-bold text-lg leading-none px-1" title="Bỏ chọn">&times;</button>'
                        + '</div></div>';
                }).join('');
            }
        }

        function renderHiddenInputs() {
            hiddenEl.innerHTML = '';
            selected.forEach(entry => {
                const memberInput = document.createElement('input');
                memberInput.type = 'hidden';
                memberInput.name = memberField;
                memberInput.value = entry.user.id;
                hiddenEl.appendChild(memberInput);

                if (entry.leader) {
                    const leaderInput = document.createElement('input');
                    leaderInput.type = 'hidden';
                    leaderInput.name = leaderField;
                    leaderInput.value = entry.user.id;
                    hiddenEl.appendChild(leaderInput);
                }
            });
        }

        function refresh() {
            renderSelected();
            renderHiddenInputs();
        }

        function addUser(id) {
            const user = users.find(u => u.id === id);
            if (!user || selected.has(id)) return;
            selected.set(id, { user, leader: false });
            refresh();
        }

        input.addEventListener('input', () => renderSuggestions(input.value));
        input.addEventListener('focus', () => renderSuggestions(input.value));

        suggestionsEl.addEventListener('click', (e) => {
            const row = e.target.closest('.mp-suggestion');
            if (!row) return;
            addUser(Number(row.dataset.id));
            input.value = '';
            hideSuggestions();
        });

        selectedEl.addEventListener('click', (e) => {
            const row = e.target.closest('[data-selected-id]');
            if (!row) return;
            const id = Number(row.dataset.selectedId);

            if (e.target.classList.contains('mp-remove')) {
                selected.delete(id);
                refresh();
                return;
            }
            if (e.target.classList.contains('mp-leader-toggle')) {
                const isNowLeader = e.target.checked;
                selected.forEach((entry, entryId) => {
                    entry.leader = isNowLeader && entryId === id;
                });
                refresh();
            }
        });

        document.addEventListener('click', (e) => {
            if (!root.contains(e.target)) hideSuggestions();
        });

        refresh();
    }

    function initMemberPickers() {
        document.querySelectorAll('.member-picker').forEach(initPicker);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initMemberPickers);
    } else {
        initMemberPickers();
    }
})();
