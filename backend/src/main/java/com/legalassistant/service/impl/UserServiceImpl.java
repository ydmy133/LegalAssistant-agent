package com.legalassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.legalassistant.common.JwtUtils;
import com.legalassistant.dto.LoginRequest;
import com.legalassistant.dto.LoginResponse;
import com.legalassistant.dto.RegisterRequest;
import com.legalassistant.entity.User;
import com.legalassistant.exception.BusinessException;
import com.legalassistant.mapper.UserMapper;
import com.legalassistant.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    @Override
    public LoginResponse register(RegisterRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new BusinessException(400, "用户名不能为空");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new BusinessException(400, "密码至少6位");
        }

        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (existing != null) {
            throw new BusinessException(400, "用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        userMapper.insert(user);

        String token = jwtUtils.generateToken(user.getId());
        return new LoginResponse(token, user.getUsername(), user.getId());
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (user == null) {
            throw new BusinessException(400, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(400, "用户名或密码错误");
        }

        String token = jwtUtils.generateToken(user.getId());
        return new LoginResponse(token, user.getUsername(), user.getId());
    }

    @Override
    public User getById(Long id) {
        return userMapper.selectById(id);
    }
}
