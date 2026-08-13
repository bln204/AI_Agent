(function () {
    'use strict';

    var block = document.querySelector('#viewer-pending-block');
    if (!block) {
        return;
    }

    var documentId = block.getAttribute('data-document-id');
    var attempts = 0;
    var MAX_ATTEMPTS = 12;

    function poll() {
        attempts++;
        fetch('/api/documents/' + encodeURIComponent(documentId) + '/viewer-status')
            .then(function (response) {
                return response.ok ? response.json() : null;
            })
            .then(function (data) {
                if (data && data.status !== 'PENDING' && data.status !== 'PROCESSING') {
                    window.location.reload();
                    return;
                }
                if (attempts < MAX_ATTEMPTS) {
                    setTimeout(poll, 5000);
                }
            })
            .catch(function () {
                if (attempts < MAX_ATTEMPTS) {
                    setTimeout(poll, 5000);
                }
            });
    }

    setTimeout(poll, 5000);
})();
