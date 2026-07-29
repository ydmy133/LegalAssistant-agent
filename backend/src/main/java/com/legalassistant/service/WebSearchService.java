package com.legalassistant.service;

import com.legalassistant.retrieval.WebSearchHit;

import java.util.List;

public interface WebSearchService {

    boolean isAvailable();

    List<WebSearchHit> search(String query);
}
