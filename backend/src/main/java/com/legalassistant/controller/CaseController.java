package com.legalassistant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.legalassistant.entity.LegalCase;
import com.legalassistant.service.CaseService;
import com.legalassistant.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService caseService;

    @PostMapping
    public Result<LegalCase> create(@RequestBody LegalCase legalCase) {
        return Result.ok(caseService.create(legalCase));
    }

    @PutMapping("/{id}")
    public Result<LegalCase> update(@PathVariable Long id, @RequestBody LegalCase legalCase) {
        legalCase.setId(id);
        return Result.ok(caseService.update(legalCase));
    }

    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        caseService.delete(id);
        return Result.ok();
    }

    @GetMapping("/{id}")
    public Result<LegalCase> getById(@PathVariable Long id) {
        return Result.ok(caseService.getById(id));
    }

    @GetMapping
    public Result<Page<LegalCase>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String caseType) {
        return Result.ok(caseService.list(page, size, caseType));
    }

    @PostMapping("/{id}/analyze")
    public Result<Map<String, String>> analyze(@PathVariable Long id) {
        String analysis = caseService.analyze(id);
        return Result.ok(Map.of("caseId", id.toString(), "analysis", analysis));
    }
}
