package com.legalassistant.controller;

import com.legalassistant.dto.LoginRequest;
import com.legalassistant.dto.LoginResponse;
import com.legalassistant.dto.RegisterRequest;
import com.legalassistant.service.UserService;
import com.legalassistant.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<LoginResponse> register(@RequestBody RegisterRequest request) {
        return Result.ok(userService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        return Result.ok(userService.login(request));
    }

    @GetMapping("/me")
    public Result<?> me() {
        Long userId = com.legalassistant.common.UserContext.getUserId();
        if (userId == null) {
            return Result.fail(401, "未登录");
        }
        return Result.ok(userService.getById(userId));
    }
}
