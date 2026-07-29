package com.legalassistant.timing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次对话请求的分阶段耗时收集器，便于定位瓶颈。
 */
public class ChatTiming {

    private final String sessionId;
    private final long startNanos;
    private final long startEpochMs;
    private final List<Stage> stages = new CopyOnWriteArrayList<>();
    private final AtomicInteger toolSeq = new AtomicInteger();

    private volatile Long firstModelActivityMs;
    private volatile Long firstToolStartMs;
    private volatile Long firstTokenMs;
    private volatile Long lastToolEndMs;
    private volatile Long streamCompleteMs;
    private volatile String modelName;
    private volatile Integer inputTokens;
    private volatile Integer outputTokens;

    public ChatTiming(String sessionId) {
        this.sessionId = sessionId;
        this.startNanos = System.nanoTime();
        this.startEpochMs = System.currentTimeMillis();
    }

    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public Stage startStage(String name) {
        return startStage(name, null);
    }

    public Stage startStage(String name, String detail) {
        Stage stage = new Stage(name, elapsedMs(), detail);
        stages.add(stage);
        return stage;
    }

    public Stage startToolStage(String toolName, String detail) {
        endOpenStageNamed("等待模型首轮", "进入工具调用: " + toolName);
        int n = toolSeq.incrementAndGet();
        if (firstToolStartMs == null) {
            firstToolStartMs = elapsedMs();
        }
        Stage stage = startStage("tool#" + n + ":" + toolName, detail);
        ChatTimingRegistry.setCurrentStage(stage);
        return stage;
    }

    public void endOpenStageNamed(String name, String detail) {
        for (Stage stage : stages) {
            if (name.equals(stage.name) && !stage.isEnded()) {
                stage.end(detail);
            }
        }
    }

    /**
     * 在当前工具阶段下挂子阶段；无工具阶段时挂到顶层。
     */
    public Stage startChildStage(String name, String detail) {
        Stage parent = ChatTimingRegistry.currentStage();
        if (parent != null) {
            return parent.child(name, detail);
        }
        return startStage(name, detail);
    }

    public void markFirstModelActivity() {
        if (firstModelActivityMs == null) {
            firstModelActivityMs = elapsedMs();
        }
    }

    public void markFirstToken() {
        if (firstTokenMs == null) {
            firstTokenMs = elapsedMs();
        }
    }

    public void markToolEnded() {
        lastToolEndMs = elapsedMs();
    }

    public void markStreamComplete() {
        streamCompleteMs = elapsedMs();
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public void setTokenUsage(Integer inputTokens, Integer outputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getStartEpochMs() {
        return startEpochMs;
    }

    public Long getFirstTokenMs() {
        return firstTokenMs;
    }

    public Long getFirstToolStartMs() {
        return firstToolStartMs;
    }

    /**
     * 汇总为可序列化结构（SSE META / 日志）。
     */
    public Map<String, Object> toMap() {
        long total = elapsedMs();
        List<Map<String, Object>> stageMaps = new ArrayList<>();
        for (Stage s : stages) {
            stageMaps.add(s.toMap());
        }

        Map<String, Object> derived = new LinkedHashMap<>();
        if (firstToolStartMs != null) {
            derived.put("接收→首次调工具", firstToolStartMs);
        } else if (firstTokenMs != null) {
            derived.put("接收→首字输出(无工具)", firstTokenMs);
        }
        if (firstTokenMs != null && firstToolStartMs != null) {
            if (firstTokenMs >= firstToolStartMs) {
                long afterTools = firstTokenMs - (lastToolEndMs != null ? lastToolEndMs : firstToolStartMs);
                if (afterTools >= 0) {
                    derived.put("工具结束→首字输出", afterTools);
                }
            } else {
                derived.put("首字输出→首次工具", firstToolStartMs - firstTokenMs);
            }
        } else if (firstTokenMs != null && firstToolStartMs == null) {
            // already covered above
        }
        if (firstTokenMs != null && streamCompleteMs != null && streamCompleteMs >= firstTokenMs) {
            derived.put("流式输出时长", streamCompleteMs - firstTokenMs);
        }
        if (firstModelActivityMs != null) {
            derived.put("接收→模型首次活动", firstModelActivityMs);
        }
        long toolTotal = 0;
        int toolCount = 0;
        for (Stage s : stages) {
            if (s.name != null && s.name.startsWith("tool#") && s.isEnded()) {
                toolTotal += s.durationMs();
                toolCount++;
            }
        }
        if (toolCount > 0) {
            derived.put("全部工具合计(" + toolCount + "次)", toolTotal);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalMs", total);
        map.put("sessionId", sessionId);
        if (modelName != null) {
            map.put("model", modelName);
        }
        if (inputTokens != null || outputTokens != null) {
            Map<String, Object> tokens = new LinkedHashMap<>();
            if (inputTokens != null) {
                tokens.put("input", inputTokens);
            }
            if (outputTokens != null) {
                tokens.put("output", outputTokens);
            }
            map.put("tokens", tokens);
        }
        map.put("milestones", milestoneMap());
        map.put("derived", derived);
        map.put("stages", stageMaps);
        map.put("summary", buildSummary(total, derived, stageMaps));
        return map;
    }

    private Map<String, Object> milestoneMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        putIfPresent(m, "firstModelActivityMs", firstModelActivityMs);
        putIfPresent(m, "firstToolStartMs", firstToolStartMs);
        putIfPresent(m, "lastToolEndMs", lastToolEndMs);
        putIfPresent(m, "firstTokenMs", firstTokenMs);
        putIfPresent(m, "streamCompleteMs", streamCompleteMs);
        return m;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Long value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private String buildSummary(long total, Map<String, Object> derived, List<Map<String, Object>> stageMaps) {
        StringBuilder sb = new StringBuilder();
        sb.append("总耗时 ").append(formatMs(total));
        for (Map.Entry<String, Object> e : derived.entrySet()) {
            sb.append(" | ").append(e.getKey()).append(' ').append(formatMs(((Number) e.getValue()).longValue()));
        }
        for (Map<String, Object> stage : stageMaps) {
            sb.append(" | ").append(stage.get("name")).append(' ').append(formatMs(((Number) stage.get("durationMs")).longValue()));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) stage.get("children");
            if (children != null) {
                for (Map<String, Object> child : children) {
                    sb.append(" / ").append(child.get("name")).append(' ')
                            .append(formatMs(((Number) child.get("durationMs")).longValue()));
                }
            }
        }
        return sb.toString();
    }

    public static String formatMs(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format("%.1fs", ms / 1000.0);
    }

    public static final class Stage {
        private final String name;
        private final long offsetMs;
        private final long startNanos;
        private volatile Long durationMs;
        private volatile String detail;
        private final List<Stage> children = Collections.synchronizedList(new ArrayList<>());

        private Stage(String name, long offsetMs, String detail) {
            this.name = name;
            this.offsetMs = offsetMs;
            this.startNanos = System.nanoTime();
            this.detail = detail;
        }

        public Stage child(String name) {
            return child(name, null);
        }

        public Stage child(String name, String detail) {
            Stage child = new Stage(name, -1, detail);
            children.add(child);
            return child;
        }

        public void end() {
            end(null);
        }

        public void end(String detail) {
            if (durationMs == null) {
                durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            }
            if (detail != null && !detail.isBlank()) {
                this.detail = detail;
            }
        }

        public boolean isEnded() {
            return durationMs != null;
        }

        public long durationMs() {
            if (durationMs != null) {
                return durationMs;
            }
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }

        public Map<String, Object> toMap() {
            if (durationMs == null) {
                end();
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("offsetMs", offsetMs);
            map.put("durationMs", durationMs);
            if (detail != null && !detail.isBlank()) {
                map.put("detail", detail);
            }
            if (!children.isEmpty()) {
                List<Map<String, Object>> childMaps = new ArrayList<>();
                for (Stage c : children) {
                    childMaps.add(c.toMap());
                }
                map.put("children", childMaps);
            }
            return map;
        }
    }
}
