package com.easyrequest.persistence;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.model.ProjectState;
import com.easyrequest.model.RequestData;
import com.easyrequest.model.UrlPrefix;
import com.easyrequest.model.RequestDocumentation;
import com.easyrequest.model.RequestTabState;
import com.easyrequest.controller.EndpointIdentity;
import com.easyrequest.controller.ControllerCatalog;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import java.util.*;

@State(name = "EasyRequest", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class RequestStore implements PersistentStateComponent<ProjectState> {
    private ProjectState state = new ProjectState();
    public RequestStore() { state.requestSchemaVersion = 1; }
    public static RequestStore get(Project project) { return com.easyrequest.compat.IdeCompat.service(project, RequestStore.class); }
    @Override public @NotNull ProjectState getState() { return state; }
    @Override public void loadState(@NotNull ProjectState state) {
        this.state = state;
        if (state.prefixes.stream().noneMatch(UrlPrefix::isAutoLocal)) {
            // Upgrade the original built-in localhost:8080 entry; preserve other custom prefixes.
            java.util.Optional<com.easyrequest.model.UrlPrefix> legacy = state.prefixes.stream().filter(p -> p.name.equals("本地") && p.url.equals("http://localhost:8080")).findFirst();
            legacy.ifPresent(prefix -> {
                if (state.selectedPrefixId.equals(prefix.id)) state.selectedPrefixId = UrlPrefix.AUTO_LOCAL_ID;
                if (state.draft != null && state.draft.prefixId.equals(prefix.id)) state.draft.prefixId = UrlPrefix.AUTO_LOCAL_ID;
                state.requests.stream().filter(r -> r.prefixId.equals(prefix.id)).forEach(r -> r.prefixId = UrlPrefix.AUTO_LOCAL_ID);
                state.requestTabs.forEach(tab -> {
                    if (tab.request.prefixId.equals(prefix.id)) tab.request.prefixId = UrlPrefix.AUTO_LOCAL_ID;
                    if (tab.baseline != null && tab.baseline.prefixId.equals(prefix.id)) tab.baseline.prefixId = UrlPrefix.AUTO_LOCAL_ID;
                });
                state.prefixes.remove(prefix);
            });
            state.prefixes.add(0, UrlPrefix.autoLocal());
        }
    }
    public void saveRequest(RequestData request) {
        RequestData saved = request.copy();
        RequestData existing = find(request.id);
        if (existing != null) copyRecordState(existing, saved);
        if (JavaCompat.isBlank(saved.id)) saved.id = UUID.randomUUID().toString();
        int index = existing == null ? -1 : state.requests.indexOf(existing);
        if (index < 0) state.requests.add(saved); else state.requests.set(index, saved);
        request.id = saved.id;
    }

    public RequestData find(String id) {
        if (JavaCompat.isBlank(id)) return null;
        return state.requests.stream().filter(request -> request.id.equals(id)).findFirst().orElse(null);
    }

    /** An old favorite becomes a catalog record plus a bookmark, without dropping any variants. */
    public void migrateCatalog(Project project) {
        if (state.requestSchemaVersion >= 1) return;
        Set<EndpointIdentity.Key> seen = new HashSet<>();
        // Legacy saves were appended; prefer the most recently saved configuration for the source route.
        for (int i = state.requests.size() - 1; i >= 0; i--) {
            RequestData request = state.requests.get(i);
            if (JavaCompat.isBlank(request.id)) request.id = UUID.randomUUID().toString();
            request.favorite = true;
            EndpointIdentity.capture(project, request);
            java.util.Optional<com.easyrequest.controller.EndpointIdentity.Key> key = EndpointIdentity.of(project, request);
            if (key.isPresent()) {
                boolean changedAddress = !request.url.equals(request.sourcePath) || !request.method.equals(request.sourceHttpMethod);
                request.legacyVariant = changedAddress || !seen.add(key.get());
                request.catalogManaged = !request.legacyVariant;
            }
        }
        if (state.requestTabs.isEmpty() && state.draft != null) {
            RequestTabState tab = new RequestTabState(); tab.request = state.draft.copy();
            RequestData saved = find(tab.request.id); tab.baseline = saved == null ? null : saved.copy();
            if (preserveLegacyAddressDraft(tab)) state.requestTabs.add(tab);
        } else state.requestTabs.forEach(this::preserveLegacyAddressDraft);
        state.requestSchemaVersion = 1;
    }

    /** Old versions allowed editing saved URLs. Keep that unsaved work as an independent draft. */
    private boolean preserveLegacyAddressDraft(RequestTabState tab) {
        RequestData request = tab.request, saved = find(request.id);
        boolean changedAddress = saved != null && (!request.url.equals(saved.url) || !request.method.equals(saved.method));
        changedAddress |= saved == null && !JavaCompat.isBlank(request.sourcePath)
                && (!request.url.equals(request.sourcePath) || !request.method.equals(request.sourceHttpMethod));
        if (!changedAddress) return false;
        request.id = ""; request.favorite = false; request.catalogManaged = false; request.sourceMissing = false; request.legacyVariant = true;
        if (tab.baseline == null) tab.baseline = saved == null ? request.copy() : saved.copy();
        tab.baseline.id = ""; tab.baseline.favorite = false; tab.baseline.catalogManaged = false;
        tab.baseline.sourceMissing = false; tab.baseline.legacyVariant = true;
        return true;
    }

    public RequestData ensureEndpoint(Project project, RequestData generated) {
        migrateCatalog(project);
        RequestData source = generated.copy();
        EndpointIdentity.capture(project, source);
        RequestData saved = EndpointIdentity.matches(project, state.requests, source).stream()
                .filter(request -> !request.legacyVariant).findFirst().orElse(null);
        return mergeEndpoint(source, saved);
    }

    private RequestData mergeEndpoint(RequestData source, RequestData saved) {
        if (saved == null) {
            saved = source.copy(); saved.id = UUID.randomUUID().toString();
            saved.favorite = false; saved.folderId = "";
            saved.prefixId = state.selectedPrefixId;
            state.requests.add(saved);
        }
        EndpointIdentity.copySource(source, saved);
        if (!JavaCompat.isBlank(source.catalog.controllerId)) saved.catalog = source.catalog.copy();
        saved.catalogManaged = true; saved.sourceMissing = false;
        RequestDocumentation.refresh(source, saved);
        return saved;
    }

    /** Apply a complete successful scan. Existing user values are never regenerated. */
    public void mergeCatalog(Project project, List<RequestData> generated) {
        mergeCatalog(project, generated, null);
    }

    /** A completed project only updates missing-source state within its own batch. */
    public void mergeCatalog(Project project, List<RequestData> generated, ControllerCatalog.ProjectBatch batch) {
        migrateCatalog(project);
        Map<EndpointIdentity.Key, RequestData> existing = new HashMap<>();
        for (RequestData request : state.requests) if (!request.legacyVariant)
            EndpointIdentity.of(project, request).ifPresent(key -> existing.putIfAbsent(key, request));
        Set<String> present = new HashSet<>();
        for (RequestData request : generated) {
            RequestData source = request.copy();
            EndpointIdentity.capture(project, source);
            java.util.Optional<com.easyrequest.controller.EndpointIdentity.Key> key = EndpointIdentity.of(project, source);
            RequestData saved = mergeEndpoint(source, key.map(existing::get).orElse(null));
            key.ifPresent(value -> existing.put(value, saved));
            present.add(saved.id);
        }
        for (RequestData request : state.requests) {
            if (!request.catalogManaged || batch != null && !batch.savedIds().contains(request.id)) continue;
            request.sourceMissing = !present.contains(request.id);
            if (batch != null) {
                request.catalog.groupId = batch.id(); request.catalog.groupName = batch.name(); request.catalog.groupPath = batch.path();
            }
        }
    }

    public void setFavorite(String id, boolean favorite) {
        RequestData request = find(id);
        if (request != null) request.favorite = favorite;
    }

    public void moveFavorite(String id, String folderId) {
        RequestData request = find(id);
        if (request != null) request.folderId = folderId;
    }

    /** Saved addresses and catalog/bookmark metadata are not editable request fields. */
    public static void copyRecordState(RequestData source, RequestData target) {
        target.id = source.id; target.url = source.url; target.method = source.method;
        target.favorite = source.favorite; target.folderId = source.folderId;
        target.catalogManaged = source.catalogManaged; target.sourceMissing = source.sourceMissing;
        target.legacyVariant = source.legacyVariant; target.catalog = source.catalog.copy();
        EndpointIdentity.copySource(source, target);
    }
}
