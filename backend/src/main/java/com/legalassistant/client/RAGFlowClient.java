package com.legalassistant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.legalassistant.config.RAGFlowProperties;
import com.legalassistant.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class RAGFlowClient {

    private final RestTemplate restTemplate;
    private final RAGFlowProperties properties;
    private final ObjectMapper objectMapper;

    public String uploadDocument(byte[] fileBytes, String displayName) {
        ensureConfigured();
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return displayName;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
        String url = properties.getBaseUrl() + "/api/v1/datasets/" + properties.getDatasetId() + "/documents";
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.POST, request, JsonNode.class);

        JsonNode root = requireSuccess(response.getBody(), "upload");
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new BusinessException("RAGFlow upload returned empty document list for " + displayName);
        }
        String documentId = textOrNull(data.get(0).get("id"));
        if (documentId == null || documentId.isBlank()) {
            throw new BusinessException("RAGFlow upload missing document id for " + displayName);
        }
        log.info("RAGFlow upload accepted: name={}, documentId={}", displayName, documentId);
        return documentId;
    }

    public void parseDocuments(List<String> documentIds) {
        ensureConfigured();
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode ids = body.putArray("document_ids");
        documentIds.forEach(ids::add);

        HttpEntity<String> request = new HttpEntity<>(body.toString(), jsonHeaders());
        String url = properties.getBaseUrl() + "/api/v1/datasets/" + properties.getDatasetId() + "/chunks";
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.POST, request, JsonNode.class);
        requireSuccess(response.getBody(), "parse");
        log.info("RAGFlow parse triggered for {} document(s)", documentIds.size());
    }

    public void pollUntilDone(String documentId) {
        ensureConfigured();
        long deadline = System.currentTimeMillis()
                + properties.getIngestPollTimeoutSeconds() * 1000L;

        while (System.currentTimeMillis() < deadline) {
            JsonNode doc = getDocument(documentId);
            String run = normalizeRun(textOrNull(doc.get("run")));
            if (isFailedRun(run)) {
                String progress = textOrNull(doc.get("progress_msg"));
                throw new BusinessException("RAGFlow parse failed for document " + documentId
                        + (progress != null ? ": " + progress : ""));
            }
            if (isDoneRun(run)) {
                log.info("RAGFlow document {} parse completed", documentId);
                return;
            }
            sleep(properties.getIngestPollIntervalMs());
        }
        throw new BusinessException("RAGFlow parse timed out for document " + documentId);
    }

    public JsonNode retrieve(String question) {
        ensureConfigured();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("question", question);
        ArrayNode datasetIds = body.putArray("dataset_ids");
        datasetIds.add(properties.getDatasetId());
        body.put("page", 1);
        body.put("page_size", properties.getPageSize());
        body.put("similarity_threshold", properties.getSimilarityThreshold());

        HttpEntity<String> request = new HttpEntity<>(body.toString(), jsonHeaders());
        String url = properties.getBaseUrl() + "/api/v1/retrieval";
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.POST, request, JsonNode.class);
        return requireSuccess(response.getBody(), "retrieval");
    }

    public void deleteDocuments(List<String> documentIds) {
        ensureConfigured();
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode ids = body.putArray("ids");
        documentIds.forEach(ids::add);

        HttpEntity<String> request = new HttpEntity<>(body.toString(), jsonHeaders());
        String url = properties.getBaseUrl() + "/api/v1/datasets/" + properties.getDatasetId() + "/documents";
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.DELETE, request, JsonNode.class);
        requireSuccess(response.getBody(), "delete");
        log.info("Deleted {} RAGFlow document(s)", documentIds.size());
    }

    public List<String> findDocumentIdsByName(String name) {
        ensureConfigured();
        List<String> ids = new ArrayList<>();
        int page = 1;
        int pageSize = 50;
        while (true) {
            // Do not pass `name` query param: RAGFlow may match documents in other datasets
            // and return code 102 ("You don't own the document ...") even when this dataset is empty.
            String url = UriComponentsBuilder
                    .fromHttpUrl(properties.getBaseUrl() + "/api/v1/datasets/"
                            + properties.getDatasetId() + "/documents")
                    .queryParam("page", page)
                    .queryParam("page_size", pageSize)
                    .toUriString();

            HttpEntity<Void> request = new HttpEntity<>(authHeaders());
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, JsonNode.class);
            JsonNode root = requireSuccess(response.getBody(), "list documents");
            JsonNode data = root.get("data");
            JsonNode docs = data != null && data.has("docs") ? data.get("docs") : data;
            if (docs == null || !docs.isArray() || docs.isEmpty()) {
                break;
            }
            for (JsonNode doc : docs) {
                String docName = textOrNull(doc.get("name"));
                if (name.equals(docName)) {
                    String id = textOrNull(doc.get("id"));
                    if (id != null && !id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
            int total = data != null && data.has("total") ? data.get("total").asInt(docs.size()) : docs.size();
            if (page * pageSize >= total) {
                break;
            }
            page++;
        }
        return ids;
    }

    public boolean existsByName(String name) {
        return !findDocumentIdsByName(name).isEmpty();
    }

    public int countChunks(String documentId) {
        JsonNode doc = getDocument(documentId);
        JsonNode chunkCount = doc.get("chunk_count");
        if (chunkCount != null && chunkCount.isNumber()) {
            return chunkCount.asInt();
        }
        JsonNode tokenCount = doc.get("token_count");
        if (tokenCount != null && tokenCount.isNumber() && tokenCount.asInt() > 0) {
            return 1;
        }
        return 0;
    }

    public JsonNode getDocument(String documentId) {
        ensureConfigured();
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.getBaseUrl() + "/api/v1/datasets/"
                        + properties.getDatasetId() + "/documents")
                .queryParam("id", documentId)
                .queryParam("page", 1)
                .queryParam("page_size", 1)
                .toUriString();

        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.GET, request, JsonNode.class);
        JsonNode root = requireSuccess(response.getBody(), "get document");
        JsonNode data = root.get("data");
        JsonNode docs = data != null && data.has("docs") ? data.get("docs") : data;
        if (docs != null && docs.isArray() && !docs.isEmpty()) {
            return docs.get(0);
        }
        if (data != null && data.isObject() && data.has("id")) {
            return data;
        }
        throw new BusinessException("RAGFlow document not found: " + documentId);
    }

    private void ensureConfigured() {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new BusinessException("RAGFlow base-url is not configured");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new BusinessException("RAGFlow api-key is not configured");
        }
        if (properties.getDatasetId() == null || properties.getDatasetId().isBlank()) {
            throw new BusinessException("RAGFlow dataset-id is not configured");
        }
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode requireSuccess(JsonNode root, String action) {
        if (root == null) {
            throw new BusinessException("RAGFlow " + action + " returned empty response");
        }
        if (root.has("code") && root.get("code").asInt(-1) != 0) {
            String message = textOrNull(root.get("message"));
            throw new BusinessException("RAGFlow " + action + " failed: "
                    + (message != null ? message : root.toString()));
        }
        return root;
    }

    private static boolean isDoneRun(String run) {
        return "done".equals(run) || "3".equals(run);
    }

    private static boolean isFailedRun(String run) {
        return "fail".equals(run) || "4".equals(run);
    }

    private static String normalizeRun(String run) {
        return run == null ? "" : run.toLowerCase(Locale.ROOT);
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
            throw new BusinessException("RAGFlow polling interrupted");
        }
    }
}
