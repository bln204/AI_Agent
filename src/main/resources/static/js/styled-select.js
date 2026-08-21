// Dropdown chọn 1 giá trị, dùng chung UI với panel gợi ý của member-picker
// (Thêm thành viên): input/nút hiển thị giá trị đang chọn + panel bên dưới
// rộng bằng đúng chiều ngang của nút đó. Vẫn giữ nguyên thẻ <select> gốc
// (ẩn đi) để không phải sửa lại logic submit/onchange đã có ở nơi khác —
// widget chỉ đồng bộ giá trị 2 chiều với select đó.
(function () {
    function closeAllPanels(except) {
        document.querySelectorAll('.ss-panel').forEach(function (panel) {
            if (panel !== except) panel.classList.add('hidden');
        });
        document.querySelectorAll('.ss-trigger[aria-expanded="true"]').forEach(function (btn) {
            if (btn !== except) btn.setAttribute('aria-expanded', 'false');
        });
    }

    function initSelect(root) {
        const native = root.querySelector('.ss-native');
        const trigger = root.querySelector('.ss-trigger');
        const label = root.querySelector('.ss-trigger-label');
        const panel = root.querySelector('.ss-panel');
        if (!native || !trigger || !label || !panel) return;

        let highlighted = -1;

        function options() {
            return Array.from(native.options);
        }

        function currentLabel() {
            const opt = native.options[native.selectedIndex];
            return opt ? opt.text : '';
        }

        function renderPanel() {
            const opts = options();
            panel.innerHTML = opts.map(function (opt, i) {
                const active = i === native.selectedIndex;
                return '<div class="ss-option flex items-center justify-between gap-3 px-4 py-2.5 cursor-pointer text-sm border-b border-slate-50 last:border-0 '
                    + (active ? 'bg-indigo-50 text-indigo-700 font-bold' : 'text-slate-700 hover:bg-slate-50') + '" '
                    + 'data-index="' + i + '" role="option" aria-selected="' + active + '">'
                    + '<span class="truncate">' + opt.text + '</span>'
                    + (active ? '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" class="flex-shrink-0"><polyline points="20 6 9 17 4 12"/></svg>' : '')
                    + '</div>';
            }).join('');
            highlighted = native.selectedIndex;
        }

        function updateLabel() {
            label.textContent = currentLabel();
        }

        function openPanel() {
            renderPanel();
            panel.classList.remove('hidden');
            trigger.setAttribute('aria-expanded', 'true');
            closeAllPanels(panel);
        }

        function closePanel() {
            panel.classList.add('hidden');
            trigger.setAttribute('aria-expanded', 'false');
        }

        function isOpen() {
            return !panel.classList.contains('hidden');
        }

        function selectIndex(i) {
            const opts = options();
            if (i < 0 || i >= opts.length) return;
            if (native.selectedIndex !== i) {
                native.selectedIndex = i;
                native.dispatchEvent(new Event('change', { bubbles: true }));
            }
            updateLabel();
            closePanel();
            trigger.focus();
        }

        trigger.addEventListener('click', function () {
            if (isOpen()) closePanel();
            else openPanel();
        });

        panel.addEventListener('click', function (e) {
            const row = e.target.closest('.ss-option');
            if (!row) return;
            selectIndex(Number(row.dataset.index));
        });

        trigger.addEventListener('keydown', function (e) {
            const opts = options();
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                if (!isOpen()) { openPanel(); return; }
                highlighted = Math.min(opts.length - 1, (highlighted < 0 ? native.selectedIndex : highlighted) + 1);
                renderHighlight();
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                if (!isOpen()) { openPanel(); return; }
                highlighted = Math.max(0, (highlighted < 0 ? native.selectedIndex : highlighted) - 1);
                renderHighlight();
            } else if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                if (isOpen() && highlighted >= 0) selectIndex(highlighted);
                else openPanel();
            } else if (e.key === 'Escape') {
                closePanel();
            }
        });

        function renderHighlight() {
            panel.querySelectorAll('.ss-option').forEach(function (row) {
                row.classList.toggle('bg-slate-50', Number(row.dataset.index) === highlighted);
            });
        }

        document.addEventListener('click', function (e) {
            if (!root.contains(e.target)) closePanel();
        });

        native.addEventListener('change', updateLabel);

        trigger.setAttribute('aria-haspopup', 'listbox');
        trigger.setAttribute('aria-expanded', 'false');
        panel.setAttribute('role', 'listbox');
        updateLabel();
    }

    function initStyledSelects() {
        document.querySelectorAll('.ss-select').forEach(initSelect);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initStyledSelects);
    } else {
        initStyledSelects();
    }
})();
