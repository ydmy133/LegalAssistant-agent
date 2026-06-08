package com.legalassistant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.legalassistant.common.UserContext;
import com.legalassistant.entity.Document;
import com.legalassistant.service.DocumentService;
import com.legalassistant.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public Result<Document> upload(@RequestParam("file") MultipartFile file) {
        Long userId = UserContext.getUserId();
        Document doc = documentService.upload(file, userId);
        return Result.ok(doc);
    }

    @GetMapping
    public Result<Page<Document>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = UserContext.getUserId();
        return Result.ok(documentService.list(page, size, userId));
    }

    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        documentService.delete(id, userId);
        return Result.ok();
    }
}
