package com.legalassistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("legal_case")
public class LegalCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNumber;
    private String title;
    private String court;
    private String caseType;
    private String parties;
    private String summary;
    private String content;
    private LocalDate judgmentDate;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
