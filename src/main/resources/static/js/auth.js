// Authentication JavaScript Functions

document.addEventListener('DOMContentLoaded', function() {
    const loginForm = document.getElementById('loginForm');

    if (loginForm) {
        loginForm.addEventListener('submit', handleLogin);
    }
});

function handleLogin(e) {
    e.preventDefault();
    
    const email = document.getElementById('email').value.trim();
    const password = document.getElementById('password').value;
    
    // Reset errors
    clearErrors();
    
    // Validate
    if (!validateEmail(email)) {
        showError('emailError', 'Email không hợp lệ');
        return;
    }
    
    if (password.length < 6) {
        showError('passwordError', 'Mật khẩu phải có ít nhất 6 ký tự');
        return;
    }
    
    // Show loading state
    const btn = document.querySelector('button[type="submit"]');
    const originalText = btn.textContent;
    btn.textContent = 'Đang xử lý...';
    btn.disabled = true;
    
        fetch('/auth/login', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ email, password })
        })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                localStorage.setItem('authToken', data.token);
                localStorage.setItem('userId', data.user.id);
                localStorage.setItem('userName', data.user.username);
                localStorage.setItem('userEmail', data.user.email);
                localStorage.setItem('userRole', data.user.role);
                localStorage.setItem('userDepartment', data.user.department);
                window.location.href = '/dashboard';
            } else {
                showError('emailError', data.message);
            }
        })
        .catch(err => {
            showError('emailError', 'Lỗi kết nối: ' + err.message);
        })
        .finally(() => {
            btn.textContent = originalText;
            btn.disabled = false;
        });
}

function validateEmail(email) {
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return emailRegex.test(email);
}

function showError(elementId, message) {
    const errorElement = document.getElementById(elementId);
    if (errorElement) {
        errorElement.textContent = message;
        errorElement.classList.add('show');
        
        // Scroll to error
        errorElement.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
}

function clearErrors() {
    const errors = document.querySelectorAll('.error');
    errors.forEach(error => {
        error.textContent = '';
        error.classList.remove('show');
    });
}
