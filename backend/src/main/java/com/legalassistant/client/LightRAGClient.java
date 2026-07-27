package com.legalassistant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.legalassistant.config.LightRAGProperties;
import com.legalassistant.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "legal.rag.provider", havingValue = "lightrag")
public class LightRAGClient {

    private final RestTemplate restTemplate;
    private final LightRAGProperties properties;
    private final ObjectMapper objectMapper;

    public String uploadDocument(byte[] fileBytes, String fileName, String fileSource) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return fileSource;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
        String url = properties.getBaseUrl() + "/documents/upload";
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.POST, request, JsonNode.class);

        JsonNode root = response.getBody();
        if (root == null) {
            throw new BusinessException("LightRAG upload returned empty response for " + fileName);
        }

        String trackId = textOrNull(root.get("track_id"));
        if (trackId == null || trackId.isBlank()) {
            throw new BusinessException("LightRAG upload missing track_id for " + fileName);
        }

        log.info("LightRAG upload accepted: file={}, fileSource={}, trackId={}, status={}",
                fileName, fileSource, trackId, textOrNull(root.get("status")));
        return trackId;
    }

    public void pollUntilDone(String trackId) {
        long deadline = System.currentTimeMillis()
                + properties.getIngestPollTimeoutSeconds() * 1000L;

        while (System.currentTimeMillis() < deadline) {
            JsonNode status = getTrackStatus(trackId);
            List<JsonNode> documents = extractDocuments(status);
            if (documents.isEmpty()) {
                sleep(properties.getIngestPollIntervalMs());
                continue;
            }

            boolean allTerminal = true;
            boolean anyFailed = false;
            for (JsonNode doc : documents) {
                String docStatus = normalizeStatus(textOrNull(doc.get("status")));
                if (isFailedStatus(docStatus)) {
                    anyFailed = true;
                }
                if (!isTerminalStatus(docStatus)) {
                    allTerminal = false;
                }
            }

            if (anyFailed) {
                String error = documents.stream()
                        .map(d -> textOrNull(d.get("error_msg")))
                        .filter(s -> s != null && !s.isBlank())
                        .findFirst()
                        .orElse("unknown error");
                throw new BusinessException("LightRAG indexing failed for track " + trackId + ": " + error);
            }

            if (allTerminal) {
                log.info("LightRAG track {} completed with {} document(s)", trackId, documents.size());
                return;
            }

            sleep(properties.getIngestPollIntervalMs());
        }

        throw new BusinessException("LightRAG indexing timed out for track " + trackId);
    }

    public JsonNode queryData(String query, String mode) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("mode", mode != null ? mode : properties.getQueryMode());

        HttpEntity<String> request = new HttpEntity<>(body.toString(), jsonHeaders());
        String url = properties.getBaseUrl() + "/query/data";
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.POST, request, JsonNode.class);

        JsonNode root = response.getBody();
        if (root == null) {
            throw new BusinessException("LightRAG query/data returned empty response");
        }

        String status = textOrNull(root.get("status"));
        if (status != null && !"success".equalsIgnoreCase(status)) {
            String message = textOrNull(root.get("message"));
            throw new BusinessException("LightRAG query failed: " + (message != null ? message : status));
        }
        return root;
    }

    public void deleteByDocIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode ids = body.putArray("doc_ids");
        docIds.forEach(ids::add);
        body.put("delete_file", true);
        body.put("delete_llm_cache", false);

        HttpEntity<String> request = new HttpEntity<>(body.toString(), jsonHeaders());
        String url = properties.getBaseUrl() + "/documents/delete_document";
        restTemplate.exchange(url, HttpMethod.DELETE, request, JsonNode.class);
        log.info("Deleted {} LightRAG document(s)", docIds.size());
    }

    public List<String> findDocIdsByFileSource(String fileSourcePrefix) {
        List<String> docIds = new ArrayList<>();
        int page = 1;
        int pageSize = 50;
        boolean hasNext = true;

        while (hasNext) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("page", page);
            body.put("page_size", pageSize);
            body.put("sort_field", "file_path");
            body.put("sort_direction", "asc");

            HttpEntity<String> request = new HttpEntity<>(body.toString(), jsonHeaders());
            String url = properties.getBaseUrl() + "/documents/paginated";
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, JsonNode.class);

            JsonNode root = response.getBody();
            if (root == null) {
                break;
            }

            JsonNode documents = root.get("documents");
            if (documents != null && documents.isArray()) {
                for (JsonNode doc : documents) {
                    String filePath = textOrNull(doc.get("file_path"));
                    if (filePath != null && filePath.startsWith(fileSourcePrefix)) {
                        String docId = textOrNull(doc.get("id"));
                        if (docId != null && !docId.isBlank()) {
                            docIds.add(docId);
                        }
                    }
                }
            }

            JsonNode pagination = root.get("pagination");
            hasNext = pagination != null && pagination.path("has_next").asBoolean(false);
            page++;
        }

        return docIds;
    }

    public boolean existsByFileSource(String fileSource) {
        return !findDocIdsByFileSource(fileSource).isEmpty();
    }

    public int countChunksByFileSource(String fileSource) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("page", 1);
        body.put("page_size", 200);
        body.put("sort_field", "file_path");
        body.put("sort_direction", "asc");

        HttpEntity<String> request = new HttpEntity<>(body.toString(), jsonHeaders());
        String url = properties.getBaseUrl() + "/documents/paginated";
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.POST, request, JsonNode.class);

        JsonNode root = response.getBody();
        if (root == null || !root.has("documents")) {
            return 0;
        }

        for (JsonNode doc : root.get("documents")) {
            String filePath = textOrNull(doc.get("file_path"));
            if (fileSource.equals(filePath)) {
                JsonNode chunksCount = doc.get("chunks_count");
                if (chunksCount != null && chunksCount.isNumber()) {
                    return chunksCount.asInt();
                }
                return 1;
            }
        }
        return 0;
    }

    public JsonNode getTrackStatus(String trackId) {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        String url = properties.getBaseUrl() + "/documents/track_status/" + trackId;
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.GET, request, JsonNode.class);
        JsonNode root = response.getBody();
        if (root == null) {
            throw new BusinessException("LightRAG track_status returned empty response for " + trackId);
        }
        return root;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.set("X-API-Key", properties.getApiKey());
        }
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private List<JsonNode> extractDocuments(JsonNode trackStatus) {
        List<JsonNode> docs = new ArrayList<>();
        JsonNode documents = trackStatus.get("documents");
        if (documents == null || documents.isNull()) {
            return docs;
        }
        if (documents.isArray()) {
            documents.forEach(docs::add);
            return docs;
        }
        if (documents.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = documents.fields();
            while (fields.hasNext()) {
                docs.add(fields.next().getValue());
            }
        }
        return docs;
    }

    private static boolean isTerminalStatus(String status) {
        return "processed".equals(status) || "failed".equals(status);
    }

    private static boolean isFailedStatus(String status) {
        return "failed".equals(status);
    }

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.toLowerCase(Locale.ROOT);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText(null);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("LightRAG polling interrupted");
        }
    }
}
