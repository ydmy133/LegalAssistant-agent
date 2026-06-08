package com.legalassistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_model_config")
public class UserModelConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String providerName;
    private String modelName;
    private String apiKey;
    private String baseUrl;
    private Integer isDefault;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
