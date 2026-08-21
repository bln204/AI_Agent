// Wires a visible dd/mm/yyyy text input to a hidden ISO (yyyy-MM-dd) input
// that actually gets submitted to the server. Native <input type="date">
// cannot be forced to a fixed display order -- its typed/displayed format
// always follows the browser/OS locale, not the page markup -- so a plain
// text input with a digit mask is the only reliable way to guarantee
// dd/mm/yyyy for every user regardless of their browser settings.
(function () {
    function pad2(n) {
        return n < 10 ? '0' + n : '' + n;
    }

    function isoToDisplay(iso) {
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
        return m ? m[3] + '/' + m[2] + '/' + m[1] : '';
    }

    function displayToIso(display) {
        var m = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec((display || '').trim());
        if (!m) return '';
        var day = parseInt(m[1], 10), month = parseInt(m[2], 10), year = parseInt(m[3], 10);
        if (month < 1 || month > 12) return '';
        var daysInMonth = new Date(year, month, 0).getDate();
        if (day < 1 || day > daysInMonth) return '';
        return year + '-' + pad2(month) + '-' + pad2(day);
    }

    function maskDigits(digits) {
        digits = digits.slice(0, 8);
        var day = digits.slice(0, 2), month = digits.slice(2, 4), year = digits.slice(4, 8);
        var out = day;
        if (month) out += '/' + month;
        if (year) out += '/' + year;
        return out;
    }

    // Today's date as yyyy-MM-dd in the BROWSER's local timezone -- deliberately
    // not `new Date().toISOString()`, which is UTC and can land on the wrong
    // calendar day for users east of UTC (e.g. right after midnight VN time).
    window.todayIso = function () {
        var d = new Date();
        return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
    };

    /**
     * displayInput: visible text input the user types dd/mm/yyyy into.
     * isoInput: hidden input (must carry the real "name" the form submits)
     * that receives the yyyy-MM-dd value expected by @DateTimeFormat(ISO.DATE).
     * nativeInput (optional): a visually-hidden <input type="date"> paired
     * with a calendar-icon button (see openDatePicker below) as a
     * click-to-pick shortcut. Its own typed/closed-state text would have
     * the exact same browser-locale ordering problem this mask exists to
     * avoid, so it's never shown (class="sr-only" in the templates) --
     * only its .value is read, which per spec is always yyyy-MM-dd
     * regardless of locale, unlike its on-screen text.
     * constraint (optional): { min: 'yyyy-mm-dd' | () => 'yyyy-mm-dd',
     * strictlyAfter: bool, message: string }. This is a client-side UX
     * convenience only -- the backend (ProjectService) re-validates the
     * same rules and is the real boundary, so a bypassed/disabled client
     * check can never persist an invalid date.
     */
    window.initDateMask = function (displayInput, isoInput, nativeInput, constraint) {
        if (!displayInput || !isoInput) return;
        constraint = constraint || {};

        if (isoInput.value) {
            displayInput.value = isoToDisplay(isoInput.value);
        }

        function currentMin() {
            var min = typeof constraint.min === 'function' ? constraint.min() : constraint.min;
            return min || '';
        }

        function sync() {
            var iso = displayToIso(displayInput.value);
            isoInput.value = iso;
            var incomplete = displayInput.value.length > 0 && !iso;
            var message = '';
            if (incomplete) {
                message = 'Ngày không hợp lệ, định dạng dd/mm/yyyy';
            } else if (iso) {
                var min = currentMin();
                if (min) {
                    var violatesMin = constraint.strictlyAfter ? iso <= min : iso < min;
                    if (violatesMin) message = constraint.message || 'Ngày không hợp lệ';
                }
            }
            displayInput.setCustomValidity(message);
            if (nativeInput) nativeInput.setCustomValidity(message);
        }
        // Exposed so a field whose valid range depends on ANOTHER field's live
        // value (e.g. "expected end date" > "start date") can be re-checked
        // via refreshDateConstraint() below when that other field changes.
        displayInput._dateMaskSync = sync;

        displayInput.addEventListener('input', function () {
            var caretWasAtEnd = displayInput.selectionEnd === displayInput.value.length;
            displayInput.value = maskDigits(displayInput.value.replace(/\D/g, ''));
            if (caretWasAtEnd) {
                displayInput.setSelectionRange(displayInput.value.length, displayInput.value.length);
            }
            sync();
        });
        displayInput.addEventListener('blur', sync);

        if (nativeInput) {
            var min = currentMin();
            if (min) nativeInput.min = min;
            if (isoInput.value) nativeInput.value = isoInput.value;
            nativeInput.addEventListener('change', function () {
                if (!nativeInput.value) return;
                displayInput.value = isoToDisplay(nativeInput.value);
                sync();
            });
        }
        sync();
    };

    // Re-validates a field and refreshes its native picker's `min` after the
    // OTHER field its constraint.min() reads from has just changed.
    window.refreshDateConstraint = function (displayInput, nativeInput, constraint) {
        if (nativeInput && constraint) {
            var min = typeof constraint.min === 'function' ? constraint.min() : constraint.min;
            if (min) nativeInput.min = min; else nativeInput.removeAttribute('min');
        }
        if (displayInput && displayInput._dateMaskSync) displayInput._dateMaskSync();
    };

    // Wired to the calendar-icon button next to each date-mask field: opens
    // its paired sr-only native <input type="date"> so users can still pick
    // from a calendar instead of typing, same convenience the native input
    // used to give for free before it was replaced by the text mask.
    window.openDatePicker = function (nativeInputId) {
        var el = document.getElementById(nativeInputId);
        if (!el) return;
        if (typeof el.showPicker === 'function') {
            try {
                el.showPicker();
                return;
            } catch (e) {
                // e.g. blocked by browser policy outside a direct user gesture --
                // fall through to focus, which still opens most browsers' picker.
            }
        }
        el.focus();
    };
})();
