// Ô tìm + chọn nhiều mục dạng chip, dùng chung UI panel gợi ý với
// member-picker (Thêm thành viên) nhưng cho danh sách mục chung (vd: dự án)
// thay vì nhân viên — không có avatar/leader. Mỗi phần tử .item-picker khai
// báo data-items-var trỏ tới biến window chứa mảng {id, label} và data-field
// là tên input sẽ được submit (nhiều input hidden cùng name, giống lúc
// <select multiple> submit nhiều value).
(function () {
    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str == null ? '' : String(str);
        return div.innerHTML;
    }

    function initPicker(root) {
        const items = window[root.dataset.itemsVar] || [];
        const field = root.dataset.field || 'itemIds';

        const input = root.querySelector('.ip-input');
        const suggestionsEl = root.querySelector('.ip-suggestions');
        const selectedEl = root.querySelector('.ip-selected');
        const hiddenEl = root.querySelector('.ip-hidden-inputs');

        const selected = new Map();

        function availableItems(query) {
            const q = (query || '').trim().toLowerCase();
            return items.filter(function (it) {
                return !selected.has(it.id) && (!q || (it.label || '').toLowerCase().includes(q));
            });
        }

        function renderSuggestions(query) {
            const list = availableItems(query).slice(0, 30);
            if (list.length === 0) {
                suggestionsEl.innerHTML = '<div class="px-4 py-3 text-sm text-slate-400">Không tìm thấy mục phù hợp.</div>';
            } else {
                suggestionsEl.innerHTML = list.map(function (it) {
                    return '<div class="ip-suggestion flex items-center px-4 py-2.5 hover:bg-slate-50 cursor-pointer border-b border-slate-50 last:border-0 text-sm font-medium text-slate-800" data-id="' + it.id + '">'
                        + escapeHtml(it.label) + '</div>';
                }).join('');
            }
            suggestionsEl.classList.remove('hidden');
        }

        function hideSuggestions() {
            suggestionsEl.classList.add('hidden');
        }

        function renderSelected() {
            if (selected.size === 0) {
                selectedEl.innerHTML = '<p class="text-xs text-slate-400 italic">Chưa chọn mục nào.</p>';
            } else {
                selectedEl.innerHTML = Array.from(selected.values()).map(function (it) {
                    return '<div class="inline-flex items-center gap-2 pl-3 pr-2 py-1.5 bg-indigo-50 text-indigo-700 rounded-full text-xs font-bold" data-selected-id="' + it.id + '">'
                        + '<span class="truncate max-w-[220px]">' + escapeHtml(it.label) + '</span>'
                        + '<button type="button" class="ip-remove text-indigo-400 hover:text-red-600 font-bold text-sm leading-none" title="Bỏ chọn">&times;</button>'
                        + '</div>';
                }).join('');
            }
        }

        function renderHiddenInputs() {
            hiddenEl.innerHTML = '';
            selected.forEach(function (it) {
                const hidden = document.createElement('input');
                hidden.type = 'hidden';
                hidden.name = field;
                hidden.value = it.id;
                hiddenEl.appendChild(hidden);
            });
        }

        function refresh() {
            renderSelected();
            renderHiddenInputs();
        }

        function addItem(id) {
            const item = items.find(function (it) { return it.id === id; });
            if (!item || selected.has(id)) return;
            selected.set(id, item);
            refresh();
        }

        input.addEventListener('input', function () { renderSuggestions(input.value); });
        input.addEventListener('focus', function () { renderSuggestions(input.value); });

        suggestionsEl.addEventListener('click', function (e) {
            const row = e.target.closest('.ip-suggestion');
            if (!row) return;
            addItem(Number(row.dataset.id));
            input.value = '';
            hideSuggestions();
        });

        selectedEl.addEventListener('click', function (e) {
            const row = e.target.closest('[data-selected-id]');
            if (!row || !e.target.classList.contains('ip-remove')) return;
            selected.delete(Number(row.dataset.selectedId));
            refresh();
        });

        document.addEventListener('click', function (e) {
            if (!root.contains(e.target)) hideSuggestions();
        });

        refresh();
    }

    function initItemPickers() {
        document.querySelectorAll('.item-picker').forEach(initPicker);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initItemPickers);
    } else {
        initItemPickers();
    }
})();
