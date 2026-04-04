// Authentication JavaScript Functions

document.addEventListener('DOMContentLoaded', function() {
    const loginForm = document.getElementById('loginForm');
    const registerForm = document.getElementById('registerForm');
    
    if (loginForm) {
        loginForm.addEventListener('submit', handleLogin);
    }
    
    if (registerForm) {
        registerForm.addEventListener('submit', handleRegister);
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

function handleRegister(e) {
    e.preventDefault();
    
    const fullname = document.getElementById('fullname').value.trim();
    const email = document.getElementById('email').value.trim();
    const phone = document.getElementById('phone').value.trim();
    const department = document.getElementById('department').value;
    const password = document.getElementById('password').value;
    const confirmPassword = document.getElementById('confirmPassword').value;
    const terms = document.getElementById('terms').checked;
    
    // Reset errors
    clearErrors();
    
    // Validate
    let isValid = true;
    
    if (fullname.length < 3) {
        showError('fullnameError', 'Họ tên phải có ít nhất 3 ký tự');
        isValid = false;
    }
    
    if (!validateEmail(email)) {
        showError('emailError', 'Email không hợp lệ');
        isValid = false;
    }
    
    if (phone && !validatePhone(phone)) {
        showError('phoneError', 'Số điện thoại không hợp lệ');
        isValid = false;
    }
    
    if (password.length < 8) {
        showError('passwordError', 'Mật khẩu phải có ít nhất 8 ký tự');
        isValid = false;
    }
    
    if (password !== confirmPassword) {
        showError('confirmPasswordError', 'Mật khẩu không khớp');
        isValid = false;
    }
    
    if (!department) {
        showError('departmentError', 'Vui lòng chọn phòng ban');
        isValid = false;
    }
    
    if (!terms) {
        showError('termsError', 'Bạn phải đồng ý với điều khoản');
        isValid = false;
    }
    
    if (!isValid) return;
    
    // Show loading state
    const btn = document.querySelector('button[type="submit"]');
    const originalText = btn.textContent;
    btn.textContent = 'Đang xử lý...';
    btn.disabled = true;
    
        fetch('/auth/register', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                username: fullname,
                email,
                password,
                department
            })
        })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                alert('Đăng ký thành công! Vui lòng đăng nhập.');
                window.location.href = '/login';
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

function validatePhone(phone) {
    const phoneRegex = /^[\d\s\-\+\(\)]+$/;
    return phoneRegex.test(phone) && phone.replace(/\D/g, '').length >= 10;
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
