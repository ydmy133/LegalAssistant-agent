package com.legalassistant.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.legalassistant.entity.LegalCase;
import com.legalassistant.mapper.LegalCaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 启动时导入示例判例（劳动争议、合同等），便于 searchCases 工具演示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegalCaseSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private final LegalCaseMapper caseMapper;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (caseMapper.selectCount(null) > 0) {
            return;
        }

        insertCase(
                "(2023)京01民终1234号",
                "用人单位未签订书面劳动合同双倍工资纠纷案",
                "北京市第一中级人民法院",
                "劳动争议",
                "劳动者张某 vs 某科技公司",
                "劳动者入职后用人单位超过一个月未签订书面劳动合同，劳动者主张双倍工资差额。法院支持劳动者请求，认定用人单位应支付未签书面劳动合同期间的双倍工资。",
                "2023-06-15",
                "本案适用《劳动合同法》第八十二条，用人单位自用工之日起超过一个月不满一年未与劳动者订立书面劳动合同的，应当向劳动者每月支付二倍的工资。"
        );
        insertCase(
                "(2022)沪0115民初5678号",
                "违法解除劳动合同赔偿金纠纷案",
                "上海市浦东新区人民法院",
                "劳动争议",
                "劳动者李某 vs 某贸易公司",
                "用人单位以业绩不达标为由解除劳动合同，但未举证证明解除合法。法院判决用人单位违法解除，应支付赔偿金。",
                "2022-11-20",
                "用人单位违法解除劳动合同的，依照劳动合同法第四十七条规定的经济补偿标准的二倍向劳动者支付赔偿金。"
        );
        insertCase(
                "(2024)粤0305民初9012号",
                "劳动合同试用期解除争议案",
                "深圳市南山区人民法院",
                "劳动争议",
                "劳动者王某 vs 某互联网公司",
                "用人单位在试用期内以不符合录用条件为由解除合同，需提供充分证据证明录用条件及考核过程。本案因证据不足认定违法解除。",
                "2024-03-08",
                "试用期用人单位解除劳动合同须证明劳动者不符合录用条件，否则构成违法解除。"
        );
        insertCase(
                "(2023)浙0108民初3456号",
                "追索劳动报酬及加班费纠纷案",
                "杭州市滨江区人民法院",
                "劳动争议",
                "劳动者陈某 vs 某制造企业",
                "劳动者主张拖欠工资及休息日加班费，用人单位未提供完整考勤记录。法院结合现有证据支持部分加班费请求。",
                "2023-09-30",
                "与争议事项有关的证据由用人单位掌握管理的，用人单位不提供的，应当承担不利后果。"
        );
        insertCase(
                "(2021)京0105民初7890号",
                "劳动合同续订无固定期限合同争议案",
                "北京市朝阳区人民法院",
                "劳动争议",
                "劳动者刘某 vs 某咨询公司",
                "劳动者连续订立二次固定期限劳动合同后，用人单位应当订立无固定期限劳动合同。用人单位拒绝续订无固定期限合同的，应支付经济补偿。",
                "2021-12-18",
                "连续订立二次固定期限劳动合同，劳动者提出续订无固定期限劳动合同的，用人单位应当订立无固定期限劳动合同。"
        );

        log.info("Seeded {} sample legal cases", 5);
    }

    private void insertCase(String caseNumber, String title, String court, String caseType,
                            String parties, String summary, String judgmentDate, String content) {
        LegalCase c = new LegalCase();
        c.setCaseNumber(caseNumber);
        c.setTitle(title);
        c.setCourt(court);
        c.setCaseType(caseType);
        c.setParties(parties);
        c.setSummary(summary);
        c.setJudgmentDate(LocalDate.parse(judgmentDate));
        c.setContent(content);
        caseMapper.insert(c);
    }
}
