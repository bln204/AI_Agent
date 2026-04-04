package com.aiagent.service;

import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy user với email: " + email));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new UsernameNotFoundException("Tài khoản đã bị vô hiệu hóa");
        }

        String roleCode = user.getRole() != null ? user.getRole().getCode() : "EMPLOYEE";
        String authority = "ROLE_" + roleCode.toUpperCase();

        return org.springframework.security.core.userdetails.User
                .withUsername(email)
                .password(user.getPassword() != null ? user.getPassword() : "")
                .authorities(List.of(new SimpleGrantedAuthority(authority)))
                .build();
    }
}
