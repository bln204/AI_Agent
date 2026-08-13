(function () {
    'use strict';

    function initDocumentViewer() {
        const container = document.querySelector('#pdf-viewer');
        if (!container) {
            return;
        }

        const documentId = container.getAttribute('data-document-id');
        const canvas = container.querySelector('#pdf-viewer-canvas');
        const pageInfo = container.querySelector('#pdf-viewer-page-info');
        const prevBtn = container.querySelector('#pdf-viewer-prev');
        const nextBtn = container.querySelector('#pdf-viewer-next');
        const zoomInBtn = container.querySelector('#pdf-viewer-zoom-in');
        const zoomOutBtn = container.querySelector('#pdf-viewer-zoom-out');
        const statusEl = container.querySelector('#pdf-viewer-status');

        if (!documentId || !canvas) {
            return;
        }

        if (typeof pdfjsLib === 'undefined') {
            if (statusEl) statusEl.textContent = 'Không thể tải trình xem PDF.';
            return;
        }

        pdfjsLib.GlobalWorkerOptions.workerSrc = '/vendor/pdfjs/pdf.worker.min.js';

        const state = {
            pdfDoc: null,
            pageNum: 1,
            scale: 1.2,
            rendering: false
        };

        function setStatus(message) {
            if (statusEl) statusEl.textContent = message || '';
        }

        function renderPage(num) {
            state.rendering = true;
            state.pdfDoc.getPage(num).then(function (page) {
                const viewport = page.getViewport({ scale: state.scale });
                const context = canvas.getContext('2d');
                canvas.height = viewport.height;
                canvas.width = viewport.width;

                const renderTask = page.render({ canvasContext: context, viewport: viewport });
                renderTask.promise.then(function () {
                    state.rendering = false;
                    if (pageInfo) {
                        pageInfo.textContent = 'Trang ' + num + ' / ' + state.pdfDoc.numPages;
                    }
                });
            });
        }

        function goToPage(num) {
            if (!state.pdfDoc || state.rendering) return;
            if (num < 1 || num > state.pdfDoc.numPages) return;
            state.pageNum = num;
            renderPage(state.pageNum);
        }

        function changeZoom(delta) {
            if (!state.pdfDoc) return;
            state.scale = Math.min(3, Math.max(0.5, state.scale + delta));
            renderPage(state.pageNum);
        }

        if (prevBtn) prevBtn.addEventListener('click', function () { goToPage(state.pageNum - 1); });
        if (nextBtn) nextBtn.addEventListener('click', function () { goToPage(state.pageNum + 1); });
        if (zoomInBtn) zoomInBtn.addEventListener('click', function () { changeZoom(0.2); });
        if (zoomOutBtn) zoomOutBtn.addEventListener('click', function () { changeZoom(-0.2); });

        // Chặn thao tác copy/context-menu thông thường trên khu vực xem tài
        // liệu — chỉ là lớp phòng vệ UI (không thay thế RBAC/backend), theo
        // đúng nguyên tắc bảo mật nội dung tài liệu nội bộ của hệ thống.
        container.addEventListener('contextmenu', function (e) { e.preventDefault(); });

        setStatus('Đang tải tài liệu...');
        fetch('/api/documents/' + encodeURIComponent(documentId) + '/viewer')
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
                return response.arrayBuffer();
            })
            .then(function (data) {
                return pdfjsLib.getDocument({ data: data }).promise;
            })
            .then(function (pdfDoc) {
                state.pdfDoc = pdfDoc;
                setStatus('');
                renderPage(state.pageNum);
            })
            .catch(function () {
                setStatus('Không thể tải bản xem trực tuyến của tài liệu này.');
            });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initDocumentViewer);
    } else {
        initDocumentViewer();
    }
})();
