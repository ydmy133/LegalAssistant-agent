package com.legalassistant.controller;

import com.legalassistant.common.UserContext;
import com.legalassistant.dto.ModelConfigRequest;
import com.legalassistant.entity.UserModelConfig;
import com.legalassistant.service.ModelConfigService;
import com.legalassistant.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/model-configs")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    @GetMapping
    public Result<List<UserModelConfig>> list() {
        Long userId = UserContext.getUserId();
        return Result.ok(modelConfigService.listByUser(userId));
    }

    @PostMapping
    public Result<UserModelConfig> create(@RequestBody ModelConfigRequest request) {
        Long userId = UserContext.getUserId();
        return Result.ok(modelConfigService.create(userId, request));
    }

    @PutMapping("/{id}")
    public Result<UserModelConfig> update(@PathVariable Long id, @RequestBody ModelConfigRequest request) {
        Long userId = UserContext.getUserId();
        return Result.ok(modelConfigService.update(id, userId, request));
    }

    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        modelConfigService.delete(id, userId);
        return Result.ok();
    }
}
