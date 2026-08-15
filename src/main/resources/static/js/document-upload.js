(function () {
    'use strict';

    const SELECTORS = {
        form: '#documentUploadForm',
        dropZone: '#dropZone',
        fileInput: '#file',
        fileName: '#fileName',
        removeFileBtn: '#removeFileBtn'
    };

    const TEXT = {
        defaultFileMessage: 'Kéo và thả tệp vào đây hoặc <strong>nhấn để chọn</strong>',
        invalidFileMessage: 'Chỉ hỗ trợ tệp PDF, DOCX, TXT hoặc XLSX.'
    };

    const ALLOWED_EXTENSIONS = ['pdf', 'docx', 'txt', 'xlsx'];

    function getElement(selector) {
        return document.querySelector(selector);
    }

    function preventDefaults(event) {
        event.preventDefault();
        event.stopPropagation();
    }

    function hasSelectedFile(fileInput) {
        return fileInput.files && fileInput.files.length > 0;
    }

    function getFileExtension(fileName) {
        const parts = fileName.split('.');
        if (parts.length < 2) {
            return '';
        }
        return parts.pop().toLowerCase();
    }

    function isValidFile(file) {
        if (!file || !file.name) {
            return false;
        }

        const extension = getFileExtension(file.name);
        return ALLOWED_EXTENSIONS.includes(extension);
    }

    function createFileList(files) {
        const dataTransfer = new DataTransfer();

        files.forEach(function (file) {
            dataTransfer.items.add(file);
        });

        return dataTransfer.files;
    }

    function setDropZoneState(dropZone, options) {
        const {
            isDragging = false,
            hasFile = false
        } = options || {};

        dropZone.classList.toggle('active', isDragging);
        dropZone.classList.toggle('has-file', hasFile);
    }

    function updateFileDisplay(fileInput, fileNameDisplay, dropZone, removeFileBtn) {
        if (hasSelectedFile(fileInput)) {
            const selectedFile = fileInput.files[0];
            fileNameDisplay.innerHTML = 'Đã chọn: <strong>' + selectedFile.name + '</strong>';
            setDropZoneState(dropZone, { isDragging: false, hasFile: true });
            removeFileBtn.hidden = false;
            return;
        }

        fileNameDisplay.innerHTML = TEXT.defaultFileMessage;
        setDropZoneState(dropZone, { isDragging: false, hasFile: false });
        removeFileBtn.hidden = true;
    }

    function resetSelectedFile(fileInput, fileNameDisplay, dropZone, removeFileBtn) {
        fileInput.value = '';
        updateFileDisplay(fileInput, fileNameDisplay, dropZone, removeFileBtn);
    }

    function assignFileToInput(fileInput, files) {
        fileInput.files = createFileList(files);
    }

    function openFilePicker(fileInput) {
        fileInput.click();
    }

    function handleInvalidFile(fileInput, fileNameDisplay, dropZone, removeFileBtn) {
        alert(TEXT.invalidFileMessage);
        resetSelectedFile(fileInput, fileNameDisplay, dropZone, removeFileBtn);
    }

    function handleDroppedFiles(event, elements) {
        const droppedFiles = Array.from(event.dataTransfer.files || []);
        if (droppedFiles.length === 0) {
            return;
        }

        const firstFile = droppedFiles[0];

        if (!isValidFile(firstFile)) {
            handleInvalidFile(
                elements.fileInput,
                elements.fileNameDisplay,
                elements.dropZone,
                elements.removeFileBtn
            );
            return;
        }

        assignFileToInput(elements.fileInput, [firstFile]);
        updateFileDisplay(
            elements.fileInput,
            elements.fileNameDisplay,
            elements.dropZone,
            elements.removeFileBtn
        );
    }

    function handleFileInputChange(elements) {
        if (hasSelectedFile(elements.fileInput)) {
            const firstFile = elements.fileInput.files[0];

            if (!isValidFile(firstFile)) {
                handleInvalidFile(
                    elements.fileInput,
                    elements.fileNameDisplay,
                    elements.dropZone,
                    elements.removeFileBtn
                );
                return;
            }
        }

        updateFileDisplay(
            elements.fileInput,
            elements.fileNameDisplay,
            elements.dropZone,
            elements.removeFileBtn
        );
    }

    function bindGlobalDragEvents(dropZone) {
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(function (eventName) {
            document.addEventListener(eventName, preventDefaults, false);
            window.addEventListener(eventName, preventDefaults, false);
            dropZone.addEventListener(eventName, preventDefaults, false);
        });

        ['dragenter', 'dragover'].forEach(function (eventName) {
            dropZone.addEventListener(eventName, function () {
                dropZone.classList.add('active');
            }, false);
        });

        ['dragleave', 'drop'].forEach(function (eventName) {
            dropZone.addEventListener(eventName, function () {
                dropZone.classList.remove('active');
            }, false);
        });
    }

    function bindDropZoneEvents(elements) {
        elements.dropZone.addEventListener('click', function (event) {
            if (event.target === elements.removeFileBtn) {
                return;
            }

            openFilePicker(elements.fileInput);
        });

        elements.dropZone.addEventListener('keydown', function (event) {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                openFilePicker(elements.fileInput);
            }
        });

        elements.dropZone.addEventListener('drop', function (event) {
            handleDroppedFiles(event, elements);
        }, false);
    }

    function bindFileInputEvents(elements) {
        elements.fileInput.addEventListener('change', function () {
            handleFileInputChange(elements);
        });

        elements.removeFileBtn.addEventListener('click', function (event) {
            event.preventDefault();
            event.stopPropagation();

            resetSelectedFile(
                elements.fileInput,
                elements.fileNameDisplay,
                elements.dropZone,
                elements.removeFileBtn
            );
        });
    }

    function toggleAccessFields() {
        const accessLevel = getElement('#accessLevel')?.value;
        const deptSection = getElement('#departmentSection');
        const projSection = getElement('#projectSection');

        if (!deptSection || !projSection) return;

        deptSection.style.display = 'none';
        projSection.style.display = 'none';

        if (accessLevel === 'DEPARTMENT') {
            deptSection.style.display = 'block';
        } else if (accessLevel === 'PROJECT') {
            projSection.style.display = 'block';
        }
    }

    function bindAccessLevelEvents() {
        const accessLevelSelect = getElement('#accessLevel');
        if (accessLevelSelect) {
            accessLevelSelect.addEventListener('change', toggleAccessFields);
        }
    }

    function bindSingleSelectCheckboxGroups() {
        document.querySelectorAll('.single-select-group').forEach(function (group) {
            group.addEventListener('change', function (event) {
                const target = event.target;
                if (!target.classList.contains('single-select-checkbox') || !target.checked) {
                    return;
                }

                group.querySelectorAll('.single-select-checkbox').forEach(function (checkbox) {
                    if (checkbox !== target) {
                        checkbox.checked = false;
                    }
                });
            });
        });
    }

    function initDocumentUpload() {
        const form = getElement(SELECTORS.form);
        const dropZone = getElement(SELECTORS.dropZone);
        const fileInput = getElement(SELECTORS.fileInput);
        const fileNameDisplay = getElement(SELECTORS.fileName);
        const removeFileBtn = getElement(SELECTORS.removeFileBtn);

        if (!form || !dropZone || !fileInput || !fileNameDisplay || !removeFileBtn) {
            return;
        }

        const elements = {
            form,
            dropZone,
            fileInput,
            fileNameDisplay,
            removeFileBtn
        };

        bindGlobalDragEvents(dropZone);
        bindDropZoneEvents(elements);
        bindFileInputEvents(elements);
        bindAccessLevelEvents();
        bindSingleSelectCheckboxGroups();

        updateFileDisplay(fileInput, fileNameDisplay, dropZone, removeFileBtn);
        toggleAccessFields();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initDocumentUpload);
    } else {
        initDocumentUpload();
    }
})();