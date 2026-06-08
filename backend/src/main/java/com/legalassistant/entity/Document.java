package com.legalassistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document")
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String fileName;
    private String filePath;
    private String fileType;
    private Long fileSize;
    private Integer chunkCount;
    private Integer status;
    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
