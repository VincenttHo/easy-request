package com.easyrequest.model;

import java.util.ArrayList;
import java.util.List;

public final class ProjectState {
    /** 0: legacy favorites contain request copies; 1: one catalog with favorite flags. */
    public int requestSchemaVersion = 0;
    public List<FolderData> folders = new ArrayList<>();
    public List<RequestData> requests = new ArrayList<>();
    public List<UrlPrefix> prefixes = new ArrayList<>();
    public List<KeyValue> globalHeaders = new ArrayList<>();
    public String selectedPrefixId = "";
    public String springProfile = "";
    public String springConfigModule = "";
    public int timeoutSeconds = 30;
    public boolean collectionsVisible = true;
    public String sidebarView = "controllers";
    public float collectionsProportion = 0.24f;
    /** Compatibility mirror for older single-request versions; null when all tabs are closed. */
    public RequestData draft;
    public List<RequestTabState> requestTabs = new ArrayList<>();
    public int activeRequestTab = 0;
    public ProjectState() {
        prefixes.add(UrlPrefix.autoLocal());
        selectedPrefixId = prefixes.get(0).id;
    }
}
