package com.legalassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.legalassistant.dto.ModelConfigRequest;
import com.legalassistant.entity.UserModelConfig;
import com.legalassistant.exception.BusinessException;
import com.legalassistant.mapper.UserModelConfigMapper;
import com.legalassistant.service.ModelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigServiceImpl implements ModelConfigService {

    private final UserModelConfigMapper modelConfigMapper;

    @Override
    public List<UserModelConfig> listByUser(Long userId) {
        return modelConfigMapper.selectList(
                new LambdaQueryWrapper<UserModelConfig>()
                        .eq(UserModelConfig::getUserId, userId)
                        .orderByDesc(UserModelConfig::getCreateTime)
        );
    }

    @Override
    @Transactional
    public UserModelConfig create(Long userId, ModelConfigRequest request) {
        if (request.getProviderName() == null || request.getProviderName().isBlank()) {
            throw new BusinessException(400, "模型提供商不能为空");
        }
        if (request.getApiKey() == null || request.getApiKey().isBlank()) {
            throw new BusinessException(400, "API Key不能为空");
        }

        if (request.getIsDefault() != null && request.getIsDefault() == 1) {
            clearDefault(userId);
        }

        UserModelConfig config = new UserModelConfig();
        config.setUserId(userId);
        config.setProviderName(request.getProviderName());
        config.setModelName(request.getModelName());
        config.setApiKey(request.getApiKey());
        config.setBaseUrl(request.getBaseUrl());
        config.setIsDefault(request.getIsDefault() != null ? request.getIsDefault() : 0);
        modelConfigMapper.insert(config);
        return config;
    }

    @Override
    @Transactional
    public UserModelConfig update(Long id, Long userId, ModelConfigRequest request) {
        UserModelConfig config = modelConfigMapper.selectById(id);
        if (config == null || !config.getUserId().equals(userId)) {
            throw new BusinessException(404, "模型配置不存在");
        }

        if (request.getIsDefault() != null && request.getIsDefault() == 1) {
            clearDefault(userId);
        }

        if (request.getProviderName() != null) config.setProviderName(request.getProviderName());
        if (request.getModelName() != null) config.setModelName(request.getModelName());
        if (request.getApiKey() != null) config.setApiKey(request.getApiKey());
        if (request.getBaseUrl() != null) config.setBaseUrl(request.getBaseUrl());
        if (request.getIsDefault() != null) config.setIsDefault(request.getIsDefault());

        modelConfigMapper.updateById(config);
        return config;
    }

    @Override
    public void delete(Long id, Long userId) {
        UserModelConfig config = modelConfigMapper.selectById(id);
        if (config == null || !config.getUserId().equals(userId)) {
            throw new BusinessException(404, "模型配置不存在");
        }
        modelConfigMapper.deleteById(id);
    }

    private void clearDefault(Long userId) {
        List<UserModelConfig> defaults = modelConfigMapper.selectList(
                new LambdaQueryWrapper<UserModelConfig>()
                        .eq(UserModelConfig::getUserId, userId)
                        .eq(UserModelConfig::getIsDefault, 1)
        );
        for (UserModelConfig c : defaults) {
            c.setIsDefault(0);
            modelConfigMapper.updateById(c);
        }
    }
}
