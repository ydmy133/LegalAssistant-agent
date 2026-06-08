package com.legalassistant.service;

import com.legalassistant.dto.LoginRequest;
import com.legalassistant.dto.LoginResponse;
import com.legalassistant.dto.RegisterRequest;
import com.legalassistant.entity.User;

public interface UserService {
    LoginResponse register(RegisterRequest request);
    LoginResponse login(LoginRequest request);
    User getById(Long id);
}
