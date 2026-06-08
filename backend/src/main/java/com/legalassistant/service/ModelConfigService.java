package com.legalassistant.service;

import com.legalassistant.dto.ModelConfigRequest;
import com.legalassistant.entity.UserModelConfig;

import java.util.List;

public interface ModelConfigService {
    List<UserModelConfig> listByUser(Long userId);
    UserModelConfig create(Long userId, ModelConfigRequest request);
    UserModelConfig update(Long id, Long userId, ModelConfigRequest request);
    void delete(Long id, Long userId);
}
