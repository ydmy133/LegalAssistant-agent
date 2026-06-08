package com.legalassistant.dto;

import lombok.Data;

@Data
public class ModelConfigRequest {
    private String providerName;
    private String modelName;
    private String apiKey;
    private String baseUrl;
    private Integer isDefault;
}
